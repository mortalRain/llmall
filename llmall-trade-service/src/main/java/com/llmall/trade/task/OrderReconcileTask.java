package com.llmall.trade.task;

import com.llmall.api.client.ShopClient;
import com.llmall.api.client.UserClient;
import com.llmall.common.constant.MqConstants;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.service.IMessageLogService;
import com.llmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对账补偿任务（最终一致性的最后一道防线）。
 *
 * 思路：**问下游"你到底处理了没有"**，而不是假设自己发成功了。
 *  - llmall-user-service 回答：这笔订单的积分流水是否存在（唯一键就是判据）；
 *  - llmall-shop-service 回答：这笔订单的消费记录是否存在。
 * 任一为"未处理"→ 触发补偿重发。下游消费幂等，所以重复投递是安全的。
 *
 * 与"重试任务"的区别：
 *  - 重试任务兜的是"消息没发出去"（发送侧失败）；
 *  - 对账任务兜的是"消息发出去了但下游没处理成功"（消费侧丢失）——
 *    例如消费者消费到一半宕机、ack 丢失、消息被误丢弃。
 *  两者覆盖的场景不同，缺一个都会漏。
 *
 * <h3>为什么对账结果必须是三态（已消费 / 未消费 / 未知）</h3>
 * "查询失败"与"查到了、结果是没有"在数据上长得很像，业务含义却相反：
 * 前者只能说明"我还不知道"，后者才是"确实没处理"。
 * 原实现把查询失败当成"已处理"（返回 true），于是 llmall-shop-service 没有实例时，
 * 6 笔订单的检查全部失败，日志却打印"下游均已处理" —— 一致性缺口被伪装成正常。
 * 现在的规则：
 *  - 未知**不计入**"均已处理"，保留待下轮重试（下游恢复后自然能查出结论）；
 *  - 统计口径分开打印，未知数 > 0 时用 WARN，连续多轮未知升级为 ERROR 提醒人工介入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReconcileTask {

    /** 只对账最近 24 小时的订单：更早的订单由人工/离线对账处理，避免任务无限膨胀 */
    private static final int RECONCILE_WINDOW_HOURS = 24;
    private static final int BATCH_SIZE = 100;

    /** 连续多少轮出现"状态未知"就升级为 ERROR（下游长时间不可用需要人看一眼，而不是一直 WARN） */
    private static final int UNKNOWN_ALERT_ROUNDS = 3;

    /** 下游处理状态：三态，未知与"未处理"必须严格区分 */
    private enum DownstreamState {
        /** 已处理 */
        CONSUMED,
        /** 明确未处理 → 需要补偿 */
        NOT_CONSUMED,
        /** 查询失败/降级 → 不知道，既不算已处理，也不触发补偿（下轮再看） */
        UNKNOWN
    }

    private final IOrderService orderService;
    private final IMessageLogService messageLogService;
    private final UserClient userClient;
    private final ShopClient shopClient;

    /** 连续出现"未知"的轮数：用于把"下游一直不可用"从 WARN 升级成需要人工介入的 ERROR */
    private final AtomicInteger consecutiveUnknownRounds = new AtomicInteger(0);

    @Scheduled(
            initialDelayString = "${llmall.reconcile.initial-delay-ms:60000}",
            fixedDelayString = "${llmall.reconcile.interval-ms:300000}")
    public void reconcilePaidOrders() {
        try {
            List<Order> orders = orderService.lambdaQuery()
                    .eq(Order::getStatus, Order.STATUS_PAID)
                    .ge(Order::getPayTime, LocalDateTime.now().minusHours(RECONCILE_WINDOW_HOURS))
                    .last("LIMIT " + BATCH_SIZE)
                    .list();
            if (orders.isEmpty()) {
                return;
            }
            int consumed = 0;
            int missing = 0;
            int unknown = 0;
            for (Order order : orders) {
                DownstreamState points = safePointsApplied(order.getId());
                DownstreamState shop = safeShopConsumed(order.getId());
                if (points == DownstreamState.UNKNOWN || shop == DownstreamState.UNKNOWN) {
                    // 只要有一项未知，这一笔就**不下结论**：不补发（避免补偿风暴）、
                    // 也不算作"已处理"（避免掩盖缺口），留到下一轮再查
                    unknown++;
                    continue;
                }
                if (points == DownstreamState.NOT_CONSUMED || shop == DownstreamState.NOT_CONSUMED) {
                    missing++;
                    log.warn("对账发现下游未处理: orderId={}, points={}, shop={}", order.getId(), points, shop);
                    messageLogService.compensate(MqConstants.BIZ_ORDER_PAID, String.valueOf(order.getId()));
                } else {
                    consumed++;
                }
            }
            report(orders.size(), consumed, missing, unknown);
        } catch (Exception e) {
            log.error("对账任务执行异常", e);
        }
    }

    /**
     * 打印本轮结果。
     * 口径必须分开：把未知混进"均已处理"正是原缺陷的核心，
     * 所以文案里"已消费 A / 未消费 B / 状态未知 C"三个数字都要出现。
     */
    private void report(int total, int consumed, int missing, int unknown) {
        if (unknown == 0) {
            consecutiveUnknownRounds.set(0);
            if (missing > 0) {
                log.warn("对账完成：检查 {} 笔，已消费 {}，未消费 {}（已补发），状态未知 0", total, consumed, missing);
            } else {
                log.info("对账完成：检查 {} 笔，已消费 {}，未消费 0，状态未知 0", total, consumed);
            }
            return;
        }
        int rounds = consecutiveUnknownRounds.incrementAndGet();
        if (rounds >= UNKNOWN_ALERT_ROUNDS) {
            log.error("【需人工介入】对账连续 {} 轮存在状态未知的订单：下游服务可能长时间不可用，一致性问题正在积累。"
                            + "检查 {} 笔，已消费 {}，未消费 {}（已补发），状态未知 {}（未计入均已处理，等待下轮重试）",
                    rounds, total, consumed, missing, unknown);
        } else {
            log.warn("对账完成：检查 {} 笔，已消费 {}，未消费 {}（已补发），状态未知 {}（下游不可用，未计入均已处理，等待下轮重试）",
                    total, consumed, missing, unknown);
        }
    }

    /**
     * 查询积分发放状态。
     *
     * <p>查询失败 = 未知，**不是**"已处理"：返回 UNKNOWN 让调用方保留待下轮确认。
     * 这里不再触发补偿，是因为补偿的前提是"确认下游没处理"，
     * 在不知道的情况下重发，既可能做无用功，也可能在下游已经过载时雪上加霜。
     */
    private DownstreamState safePointsApplied(Long orderId) {
        try {
            return userClient.isPointsApplied(orderId) ? DownstreamState.CONSUMED : DownstreamState.NOT_CONSUMED;
        } catch (Exception e) {
            log.warn("查询积分发放状态失败，本轮按‘未知’处理（不计入均已处理）: orderId={}", orderId);
            log.debug("查询积分发放状态失败详情", e);
            return DownstreamState.UNKNOWN;
        }
    }

    private DownstreamState safeShopConsumed(Long orderId) {
        try {
            return shopClient.isOrderConsumed(orderId) ? DownstreamState.CONSUMED : DownstreamState.NOT_CONSUMED;
        } catch (Exception e) {
            log.warn("查询门店销量处理状态失败，本轮按‘未知’处理（不计入均已处理）: orderId={}", orderId);
            log.debug("查询门店销量处理状态失败详情", e);
            return DownstreamState.UNKNOWN;
        }
    }
}
