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
 * 【注意】核销事件（order.verified）的交换机/队列/绑定必须由生产端显式声明：
 * 本项目其它事件（order.paid 等）的拓扑是消费端用 {@code @RabbitListener(bindings=...)}
 * 顺带声明的，而核销事件的消费方（门店结算）本期还没接，且 topic 交换机对
 * "没有绑定队列的路由键"是静默丢弃 —— 不声明队列的话，核销事件发出去当场就没了，
 * 且不会有任何报错。队列先声明、消费端后接符合这个系统的整体取舍：
 * 核销事实已经落在订单表里，MQ 只是通知通道，允许延迟但不该丢。
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
