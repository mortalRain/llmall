package com.llmall.content.task;

import com.llmall.content.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地消息重试任务：兜住"消息已落库但没投递出去"（事务提交后进程崩溃、MQ 短暂不可用、网络抖动），
 * 退避策略见 MessageLogServiceImpl（5s → 10s → 20s …… 上限 10 分钟）。
 *
 * 【注意】必须配合 ContentApplication 上的 @EnableScheduling 才生效，漏了注解不会有任何报错：
 * @Scheduled 方法静默不执行，message_log 里 status=3 的行一直躺着，日志里连异常都看不到。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRetryTask {

    private final IMessageLogService messageLogService;

    /**
     * 间隔可配（llmall.message.retry-interval-ms），默认 15s。
     * 用 fixedDelay 而非 fixedRate：上一轮没跑完不该再叠一轮。
     */
    @Scheduled(fixedDelayString = "${llmall.message.retry-interval-ms:15000}")
    public void retryPendingMessages() {
        try {
            int success = messageLogService.retryPending(50);
            if (success > 0) {
                log.info("本地消息重试完成，本次成功投递 {} 条", success);
            }
        } catch (Exception e) {
            // 定时任务不能因单次异常而终止（Spring 仍会继续调度），但异常必须留在日志里
            log.error("本地消息重试任务执行异常", e);
        }
    }
}
