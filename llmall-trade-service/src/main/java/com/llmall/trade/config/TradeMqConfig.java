package com.llmall.trade.config;

import com.llmall.common.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 交易域的 MQ 拓扑声明。
 *
 * <h3>为什么核销事件要在这里显式声明交换机/队列/绑定</h3>
 * 本项目其它事件（order.paid 等）的拓扑是"由消费端用 {@code @RabbitListener(bindings=...)}
 * 顺带声明"的，这在"消费者一定在线"的前提下没问题。但核销事件不一样：
 * 它的消费方（门店结算）本期还没接，而且 topic 交换机对"没有绑定队列的路由键"
 * 是**静默丢弃**——不声明队列的话，核销事件发出去当场就没了，
 * "为结算留的钩子"会变成一句空话，且不会有任何报错。
 * 所以生产端自己把 [交换机 → 队列 → 路由键] 落下来：消息先攒在队列里，
 * 消费方什么时候接、按什么节奏消费都不影响事实的留存。
 *
 * 注意队列先声明、消费端后接也符合这个系统的整体取舍：核销事实已经落在订单表里，
 * MQ 只是"通知"通道，允许延迟但不该丢。
 */
@Configuration
public class TradeMqConfig {

    /** 交易域交换机（与 order.paid 共用，避免交换机数量随事件数量线性增长） */
    @Bean
    public TopicExchange tradeExchange() {
        return new TopicExchange(MqConstants.TRADE_EXCHANGE, true, false);
    }

    /** 门店核销结算队列（durable：Broker 重启后消息不丢） */
    @Bean
    public Queue orderVerifiedSettleQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_VERIFIED_SETTLE_QUEUE).build();
    }

    @Bean
    public Binding orderVerifiedSettleBinding(Queue orderVerifiedSettleQueue, TopicExchange tradeExchange) {
        return BindingBuilder.bind(orderVerifiedSettleQueue)
                .to(tradeExchange)
                .with(MqConstants.ORDER_VERIFIED_KEY);
    }
}
