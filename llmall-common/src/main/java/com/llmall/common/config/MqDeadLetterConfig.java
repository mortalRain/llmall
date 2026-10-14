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
 * 统一死信拓扑：重试耗尽的消息经 llmall.dlx 按 "原队列名.dead" 路由到本服务的服务名.dead.letter.queue，
 * 打一条带原队列、异常、traceId 的 ERROR 等人工/补偿处理。
 *
 * 【注意】死信队列按服务分而非全项目共用：同一路由键（如 order.paid）有多个订阅方，共用队列会让
 * "谁的消息"无从判断；故绑定用 topic 而非 fanout，否则一条死信会广播进所有服务的死信队列各存一份。
 */
@Configuration
@ConditionalOnClass(RabbitTemplate.class)
public class MqDeadLetterConfig {

    private static final Logger log = LoggerFactory.getLogger(MqDeadLetterConfig.class);

    @Bean
    public TopicExchange deadLetterExchange() {
        return ExchangeBuilder.topicExchange(DeadLetterPublisher.DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    /** 本服务的死信队列：{@code <spring.application.name>.dead.letter.queue}。 */
    @Bean
    public Queue deadLetterQueue(@Value("${spring.application.name:unknown-service}") String applicationName) {
        return QueueBuilder.durable(applicationName + DeadLetterPublisher.DEAD_LETTER_QUEUE_SUFFIX).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange)
                .with("#" + DeadLetterPublisher.DEAD_LETTER_ROUTING_KEY_SUFFIX);
    }

    /**
     * 重试耗尽后的回收器：把消息搬到死信队列，而不是原地 requeue 热循环、也不是直接丢弃。
     *
     * Spring Boot 只要发现容器里有 {@code MessageRecoverer} Bean 就会装进监听容器的重试拦截器，
     * 无需改任何消费者代码。
     *
     * 【注意】不额外给死信队列配消费者：死信的 body 往往无法反序列化（转换失败正是进死信的常见原因），
     * 挂 @RabbitListener 会再走一遍同样的转换、再失败一次，变成"搬运进死信 → 转换失败 → 又被 reject 丢掉"，
     * 队列看着是空的而问题照样丢；死信的正确用法是留在队列里等人工/补偿流程处理。
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
