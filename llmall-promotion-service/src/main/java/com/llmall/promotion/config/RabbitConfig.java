package com.llmall.promotion.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀异步落库的 MQ 拓扑。
 *
 * 【为什么异步落库选 RabbitMQ，而不是本地线程池】
 * 秒杀接口的第一原则是"快且不阻塞"：Redis 预扣成功后，用户请求就该返回凭证，
 * 落库（插 user_voucher + 扣 DB 库存 + 写幂等流水）耗时几十毫秒且可能因 DB 抖动而失败。
 * 两种方案都异步，但可靠性差别很大：
 *   - 线程池：任务只在内存里，进程被重启/被杀（本项目受内存限制，抖动时更常见）任务直接蒸发，
 *     结果是"Redis 名额已扣、用户永远拿不到券"，而且没有任何痕迹可查，只能靠人工对账；
 *   - RabbitMQ：消息持久化在 broker，消费者手动 ack，落库失败可以重投，
 *     配合 consume_record 唯一键做幂等，天然解决"至少一次投递"带来的重复落库问题。
 * 代价是多了一次网络往返和一个 broker 依赖，所以这里用"发送失败即回补 Redis 预扣"的补偿来兜住 broker 不可用的极端情况。
 *
 * 【为什么显式声明交换机/队列，而不是只在 @RabbitListener 上用 @QueueBinding 声明】
 * 队列是**生产者的发送目标**：显式声明可以保证应用启动时就创建好持久化队列，
 * 避免"消费者还没起、生产者先发消息"时消息因无队列而丢失。
 */
@Configuration
public class RabbitConfig {

    /** 营销域交换机（topic） */
    public static final String PROMOTION_EXCHANGE = "promotion.topic";

    /** 路由键：秒杀券已预扣成功，需要异步落库 */
    public static final String SECKILL_ORDER_KEY = "voucher.seckill.ordered";

    /** 队列：秒杀落库（本服务自己消费，所以队列前缀即服务名） */
    public static final String SECKILL_ORDER_QUEUE = "promotion.seckill.order.queue";

    @Bean
    public TopicExchange promotionExchange() {
        // durable(true)：broker 重启后交换机仍在，否则队列绑定会一起消失
        return new TopicExchange(PROMOTION_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillOrderQueue() {
        // durable：消息落盘，broker 重启不丢（配合发送端持久化消息，才叫"可靠"）
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, TopicExchange promotionExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(promotionExchange).with(SECKILL_ORDER_KEY);
    }
}
