package com.llmall.api.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 事件：订单到店核销完成（status 2 → 7）。
 *
 * 生产者：llmall-trade-service（核销成功后投递）
 * 消费者：llmall-shop-service（门店结算/对账，后续里程碑接入；本期先落队列保住消息）
 *
 * 为什么核销也要发事件：核销是"履约完成"的唯一凭据 —— 门店按核销笔数结算、
 * 平台据此做对账。若只改数据库状态，结算方只能轮询 trade 库的 status=7，
 * 那就把交易域的表结构变成了事实上的跨域接口。
 *
 * 与 OrderPaidEvent 的关键区别：核销是**单条订单的终态动作**，没有"派生出多个下游动作"
 * 的必要，所以这里不走"本地消息表 + 对账补偿"那套重装备 —— 核销本身已经落库，
 * 消息丢了也只是"结算晚一点"，用日志 + 队列兜住即可（下单路径上的取舍见 createOrder）。
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

    /** 消费用户 */
    private Long userId;

    /** 订单实付金额（分）—— 结算分成以核销订单的实付金额为基数 */
    private Integer totalFee;

    /** 发生时间（epoch 毫秒） */
    private Long occurredAt;
}
