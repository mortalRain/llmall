package com.llmall.trade.task;

import com.llmall.trade.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地消息重试任务。
 *
 * 兜住的是这种场景：事务提交后进程崩溃 / MQ 短暂不可用 / 网络抖动，
 * 导致"消息已落库但没投递出去"。没有这个任务，消息就永远躺在表里。
 * 退避策略在 MessageLogServiceImpl 里：5s → 10s → 20s …… 上限 10 分钟。
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
