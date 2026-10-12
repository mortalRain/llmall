package com.llmall.trade.task;

import com.llmall.trade.domain.po.Order;
import com.llmall.trade.guard.OrderCloseGuard;
import com.llmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时未关单订单的兜底扫描。
 *
 * <h3>为什么延迟消息之外还需要它</h3>
 * 延迟消息是"到点自动关单"的主路径，但它依赖 MQ：Broker 重启、队列被误删、
 * 消息在重投上限内被丢弃、服务长时间停机导致延迟消息到期时没人消费……
 * 任何一种都会留下"永远停在待付款"的订单 —— 库存被占、券被锁，用户却什么都没得到。
 * 兜底扫描只问一个纯本地的问题："库里还有没有创建时间已经超过超时时间、
 * 但状态仍是待付款的订单？" 有就逐笔走同一套关单裁决逻辑，把主路径漏掉的补上。
 *
 * <h3>为什么它也必须遵守"未知不关单"</h3>
 * 扫描同样要查支付状态才能关单（见 {@link OrderCloseGuard}）。如果这里图省事直接 cancel，
 * 就等于给"支付服务不可用时照样关单"留了后门，E1 的修复会被这条路径重新打穿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutScanTask {

    /** 单轮最多处理多少笔：避免一次积压几十万笔把数据库和下游打满，剩下的下轮继续 */
    private static final int BATCH_SIZE = 200;

    private final IOrderService orderService;
    private final OrderCloseGuard orderCloseGuard;

    /** 订单支付超时时间（秒），与延迟消息用的是同一个配置项，保证两条路径判定口径一致 */
    @Value("${llmall.trade.order-timeout-seconds:900}")
    private long orderTimeoutSeconds;

    @Scheduled(fixedDelayString = "${llmall.trade.order-scan-interval-ms:300000}")
    public void scanTimeoutUnpaidOrders() {
        // 整个任务体包 try/catch：定时任务抛异常会被调度线程记日志后终止后续执行，
        // 一次数据库抖动就再也不会扫描了 —— 兜底机制必须自己先"兜得住"。
        try {
            LocalDateTime deadline = LocalDateTime.now().minusSeconds(orderTimeoutSeconds);
            List<Order> orders = orderService.lambdaQuery()
                    .eq(Order::getStatus, Order.STATUS_UNPAID)
                    .lt(Order::getCreateTime, deadline)
                    .last("LIMIT " + BATCH_SIZE)
                    .list();
            if (orders == null || orders.isEmpty()) {
                return;
            }
            int paid = 0;
            int closed = 0;
            int skipped = 0;
            int unknown = 0;
            int failed = 0;
            for (Order order : orders) {
                try {
                    switch (orderCloseGuard.settle(order.getId())) {
                        case MARKED_PAID:
                            paid++;
                            break;
                        case CLOSED:
                            closed++;
                            break;
                        case UNKNOWN:
                            unknown++;
                            break;
                        default:
                            skipped++;
                    }
                } catch (Exception e) {
                    // 单笔失败不影响其它订单：一笔脏数据不能让整轮扫描中断
                    failed++;
                    log.error("兜底扫描处理订单失败: orderId={}", order.getId(), e);
                }
            }
            if (unknown > 0) {
                // 有"未知"就必须告警：这说明支付服务可能不可用，积压订单只是表象
                log.warn("超时订单兜底扫描完成：待扫描 {} 笔，已标记支付 {}，已关单 {}，无需处理 {}，状态未知 {}（支付服务可能不可用，保持待付款等待下轮），处理失败 {}",
                        orders.size(), paid, closed, skipped, unknown, failed);
            } else {
                log.info("超时订单兜底扫描完成：待扫描 {} 笔，已标记支付 {}，已关单 {}，无需处理 {}，处理失败 {}",
                        orders.size(), paid, closed, skipped, failed);
            }
        } catch (Exception e) {
            log.error("超时订单兜底扫描任务执行异常（本轮跳过，下一轮继续）", e);
        }
    }
}
