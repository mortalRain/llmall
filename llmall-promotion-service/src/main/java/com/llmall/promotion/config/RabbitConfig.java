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
 * 异步落库选 RabbitMQ 而非本地线程池：线程池任务只在内存里，进程重启/被杀即蒸发，
 * 会出现"Redis 名额已扣、用户拿不到券且无痕迹可查"；RabbitMQ 消息持久化在 broker，
 * 消费者手动 ack + consume_record 唯一键幂等，可安全支撑至少一次投递下的重投。
 * 代价是多一次网络往返与一个 broker 依赖，故用"发送失败即回补 Redis 预扣"兜底。
 *
 * 显式声明交换机/队列，而非只在 @RabbitListener 上用 @QueueBinding：队列是生产者的发送目标，
 * 显式声明可保证应用启动时就创建好持久化队列，避免"消费者还没起、生产者先发消息"时消息因无队列丢失。
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
        // durable：消息落盘，broker 重启不丢（配合发送端持久化消息才算可靠）
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, TopicExchange promotionExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(promotionExchange).with(SECKILL_ORDER_KEY);
    }
}
