package com.llmall.common.config;

import com.llmall.common.mq.DeadLetterPublisher;
import com.llmall.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一死信与消费失败告警（B3）。
 *
 * <p>【改前状态】全项目没有任何死信拓扑：重试 3 次仍失败的消息，在
 * {@code default-requeue-rejected=true}（Spring Boot 默认）下会被立刻 requeue 回原队列，
 * 消费者马上又拿到它 —— 满速热循环；把该值改成 false 又会变成静默丢弃。
 * 实测（2026-10-13，llmall-user-service）：往 {@code user.points.order.paid.queue} 发一条非法 JSON，
 * 消息在处理失败后**立刻从队列消失**，只在日志留一段栈。
 *
 * <p>【改后拓扑】
 * <pre>
 *   消费者失败 → 重试（shared-mq.yaml: max-attempts=3）→ 仍失败
 *        → MessageRecoverer → 投到 llmall.dlx（topic）
 *        → 按 "&lt;原队列名&gt;.dead" 路由 → &lt;服务名&gt;.dead.letter.queue（本服务自己的死信队列）
 *        → DeadLetterAlertListener 打一条 ERROR（带原队列、异常、traceId）等人工/补偿处理
 * </pre>
 *
 * <p>【为什么死信队列按服务分，而不是全项目一个】一是日志：各服务的死信告警应该出现在自己的日志里；
 * 二是治理：同一个路由键（如 {@code order.paid}）有多个订阅方，共用一个队列会让"谁的消息"无从判断。
 */
@Configuration
@ConditionalOnClass(RabbitTemplate.class)
public class MqDeadLetterConfig {

    private static final Logger log = LoggerFactory.getLogger(MqDeadLetterConfig.class);

    /** 死信交换机（topic，durable）。 */
    @Bean
    public TopicExchange deadLetterExchange() {
        return ExchangeBuilder.topicExchange(DeadLetterPublisher.DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    /** 本服务的死信队列：{@code <spring.application.name>.dead.letter.queue}。 */
    @Bean
    public Queue deadLetterQueue(@Value("${spring.application.name:unknown-service}") String applicationName) {
        return QueueBuilder.durable(applicationName + DeadLetterPublisher.DEAD_LETTER_QUEUE_SUFFIX).build();
    }

    /**
     * 绑定：{@code #.dead} 接住所有 "原队列名.dead" 的投递。
     * 用 topic 而不是 fanout：fanout 会把每个服务的死信广播给所有服务的死信队列，
     * 一条死信在 6 个队列里各存一份，"谁的消息"这个信息反而丢了。
     */
    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange)
                .with("#" + DeadLetterPublisher.DEAD_LETTER_ROUTING_KEY_SUFFIX);
    }

    /**
     * 重试耗尽后的回收器：把消息搬到死信队列（而不是原地 requeue 热循环、也不是直接丢弃）。
     *
     * <p>Spring Boot 只要发现容器里有 {@code MessageRecoverer} Bean，就会把它装进监听容器的重试拦截器，
     * 所以这里不需要改任何消费者代码。
     *
     * <p>【告警就在这一刻打】不额外给死信队列配消费者。原因是一次实测教训：
     * 死信消息的 body 往往是**无法反序列化**的内容（转换失败正是进死信的常见原因），
     * 给死信队列挂一个 @RabbitListener 会再走一遍同样的转换、再失败一次，
     * 结果是"消息被搬运进死信 → 告警消费者转换失败 → 又被 reject 丢掉"，
     * 死信队列看着是空的，问题照样丢。死信的正确用法是**留在队列里**等人工/补偿流程处理，
     * 告警由本回收器在搬运成功时打（含原队列、异常、traceId）。
     * 查看方式：管理台 http://192.168.189.128:15672 → Queues → &lt;服务名&gt;.dead.letter.queue，
     * 或 {@code docker exec mq rabbitmqctl list_queues -p /llmall name messages}。
     */
    @Bean
    public MessageRecoverer deadLetterRecoverer(RabbitTemplate rabbitTemplate) {
        return (message, cause) -> {
            if (DeadLetterPublisher.alreadyDeadLettered(message.getMessageProperties())) {
                // 死信消息本身又失败：只告警不搬运，否则会形成"死信的死信"新循环
                log.error("死信消息再次处理失败（不再二次搬运，请人工处理）: 队列={} traceId={} 原因={}",
                        message.getMessageProperties().getConsumerQueue(),
                        message.getMessageProperties().getHeaders().get(TraceContext.HEADER),
                        cause == null ? "未知" : cause.toString());
                return;
            }
            DeadLetterPublisher.publish(rabbitTemplate, message, message.getMessageProperties().getConsumerQueue(), cause);
        };
    }
}
