package com.llmall.api.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 事件：订单到店核销完成（status 2 → 7）。
 *
 * 生产者：llmall-trade-service（核销成功后投递）；消费者：llmall-shop-service（门店结算/对账）。
 *
 * 【注意】核销是履约完成的唯一凭据，若只改库状态，结算方就只能轮询 trade 库的 status=7，
 * 等于把交易域的表结构变成跨域接口。核销本身已落库，消息丢失只是"结算晚一点"，
 * 所以这里用日志 + 队列兜住，不上"本地消息表 + 对账补偿"。
 */
@Data
@Accessors(chain = true)
public class OrderVerifiedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键：订单号（同一订单只会核销一次，下游可据此去重） */
    private String bizId;

    private Long orderId;

    /** 核销发生的门店（结算主体） */
    private Long shopId;

    private Long userId;

    /** 订单实付金额（分），结算分成以核销订单的实付金额为基数 */
    private Integer totalFee;

    /** 发生时间（epoch 毫秒） */
    private Long occurredAt;
}
