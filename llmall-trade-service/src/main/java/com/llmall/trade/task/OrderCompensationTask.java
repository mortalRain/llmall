package com.llmall.trade.task;

import com.llmall.trade.domain.po.Order;
import com.llmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCompensationTask {
    private final IOrderService orders;

    @Scheduled(fixedDelayString="${llmall.trade.compensation-interval-ms:30000}")
    public void recover() {
        try {
            for (Order order : orders.lambdaQuery().in(Order::getStatus, 8, 9)
                    .orderByAsc(Order::getUpdateTime, Order::getId).last("LIMIT 100").list()) {
                try { orders.recoverCompensation(order.getId()); }
                catch (RuntimeException | AssertionError failure) {
                    orders.lambdaUpdate().eq(Order::getId, order.getId()).in(Order::getStatus, 8, 9)
                            .set(Order::getUpdateTime, LocalDateTime.now()).update();
                    log.error("订单补偿失败，保留待处理状态: orderId={}", order.getId(), failure);
                }
            }
        } catch (RuntimeException failure) { log.error("订单补偿扫描失败", failure); }
    }
}
