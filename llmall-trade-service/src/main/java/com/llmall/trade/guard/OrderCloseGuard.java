package com.llmall.trade.guard;

import com.llmall.api.client.PayClient;
import com.llmall.api.dto.PayOrderStatusDTO;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * "超时订单该不该关"的唯一裁决点：关单的两个入口（延迟消息 {@code OrderDelayMessageListener}、
 * 兜底扫描 {@code OrderTimeoutScanTask}）都走这里，把判断收敛到一处，这条规则才是不变量而不是巧合。
 *
 * 【注意】核心不变量：未知不等于未支付。只有明确查到"没有支付单"或"支付单未支付/已关闭"才关单；
 * 查询失败、降级、返回空一律按"未知"处理 —— 不关单、不回滚库存，订单保持待付款等下轮或人工处理。
 * 宁可让一笔真没付钱的订单多挂一会儿，也不能把用户已经付过钱的订单关掉退库存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCloseGuard {

    /** 支付单状态：3 支付成功（pay_order.status，与 llmall-pay-service 的 PayStatus.TRADE_SUCCESS 对齐） */
    private static final int PAY_STATUS_SUCCESS = 3;

    private final IOrderService orderService;
    private final PayClient payClient;

    /** 一次"该不该关单"的判断结果 */
    public enum Outcome {
        /** 确认已支付，已把订单标记为已支付 */
        MARKED_PAID,
        /** 确认未支付，已关单并回滚库存 */
        CLOSED,
        /** 订单不存在或状态已不是"待付款"，无需处理（重复投递的常态） */
        SKIPPED,
        /** 支付状态无法确认：未做任何状态变更，调用方负责重投/告警 */
        UNKNOWN
    }

    /**
     * 判断并执行：查询支付状态 → 已支付则标记、确认未支付则关单、未知则什么都不做。
     * 幂等：底层用的都是条件更新（1→2 / 1→5），重复调用不会产生副作用。
     */
    public Outcome settle(Long orderId) {
        Order order = orderService.getById(orderId);
        if (order == null) {
            return Outcome.SKIPPED;
        }
        // 关单前必须复核订单状态：延迟消息可能晚到、扫描与用户支付可能并发，只有仍是"待付款"才继续判断
        if (order.getStatus() == null || order.getStatus() != Order.STATUS_UNPAID) {
            return Outcome.SKIPPED;
        }
        PayOrderStatusDTO status;
        try {
            status = payClient.queryPayOrderByBizOrderNo(orderId);
        } catch (Exception e) {
            // 降级实现本身不会抛异常，但序列化/连接等异常仍可能冒泡，同样按"未知"处理
            status = PayOrderStatusDTO.unknown("查询支付状态异常: " + e.getClass().getSimpleName());
        }
        if (status == null || !status.isDetermined()) {
            // 【关键分支】未知 → 不做任何业务决定，绝不能"顺手"关单：关单会退库存、退券，而用户可能刚付过钱
            log.warn("支付状态未知，本轮不做关单/改状态动作，订单保持待付款: orderId={}, 原因={}",
                    orderId, status == null ? "支付状态查询返回空" : status.getUnknownReason());
            return Outcome.UNKNOWN;
        }
        if (status.isFound()
                && status.getPayStatus() != null
                && status.getPayStatus() == PAY_STATUS_SUCCESS) {
            // 确认已支付：补上"支付成功但订单没被改状态"的缺口（例如 pay.success 消息丢了）
            orderService.markOrderPaySuccess(orderId);
            log.info("支付状态已确认为已支付，订单已标记为已支付: orderId={}", orderId);
            return Outcome.MARKED_PAID;
        }
        // 走到这里 = 确认未支付：found=false（从没发起过支付）或 payStatus∈{0,1,2}（未提交/待支付/已关闭）
        if (status.isFound() && !Integer.valueOf(0).equals(status.getPayStatus())
                && !Integer.valueOf(1).equals(status.getPayStatus())
                && !Integer.valueOf(2).equals(status.getPayStatus())) {
            return Outcome.UNKNOWN;
        }
        orderService.cancelOrder(orderId);
        log.info("支付状态已确认为未支付，关单并回滚库存: orderId={}, found={}, payStatus={}",
                orderId, status.isFound(), status.getPayStatus());
        return Outcome.CLOSED;
    }
}
