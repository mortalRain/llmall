package com.llmall.shop.task;

import com.llmall.shop.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地消息重试任务（门店域）。
 *
 * 兜住"消息已落库但没投递出去"：事务提交后进程崩溃 / MQ 短暂不可用 / 网络抖动都会造成这种缺口，
 * 没有它消息会永远躺在表里，表现为"评价成功、门店热度与搜索索引不更新"且无任何信号。
 * 退避策略在 MessageLogServiceImpl：5s → 10s → 20s …… 上限 10 分钟。
 * 与 {@link ShopSearchReconcileTask} 的分工：本任务兜发送侧失败，对账任务兜"发出去了但下游没处理成功"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRetryTask {

    private final IMessageLogService messageLogService;

    @Scheduled(fixedDelayString = "${llmall.message.retry-interval-ms:15000}")
    public void retryPendingMessages() {
        try {
            int success = messageLogService.retryPending(50);
            if (success > 0) {
                log.info("本地消息重试完成，本次成功投递 {} 条", success);
            }
        } catch (Exception e) {
            // 定时任务绝不能因为单次异常而终止（Spring 的 fixedDelay 任务抛异常后仍会继续，但要留日志）
            log.error("本地消息重试任务执行异常", e);
        }
    }
}
