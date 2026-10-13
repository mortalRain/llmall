package com.llmall.content.task;

import com.llmall.content.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地消息重试任务（内容域，审计 B4 生产端第三段）。
 *
 * 兜住的是这种场景：事务提交后进程崩溃 / MQ 短暂不可用 / 网络抖动，
 * 导致"消息已落库但没投递出去"。没有这个任务，消息就永远躺在 llmall-content.message_log 里 ——
 * 表现就是"评价发布成功、门店热度/评分不更新"，而且没有任何信号。
 * 退避策略在 MessageLogServiceImpl 里：5s → 10s → 20s …… 上限 10 分钟。
 *
 * <p>⚠️ 这个类必须配合 {@code ContentApplication} 上的 {@code @EnableScheduling} 才有效：
 * 内容域原来是"零定时任务"的服务，没有那个注解，而**漏了注解不会有任何报错**——
 * 方法静静地不执行，message_log 里 status=3 的行一直躺着，日志里连一条异常都看不到。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRetryTask {

    private final IMessageLogService messageLogService;

    /**
     * 间隔可配（llmall.message.retry-interval-ms），默认 15s。
     * 用 fixedDelay 而不是 fixedRate：上一轮没跑完不该再叠一轮（重试本身会访问 MQ 与 DB）。
     */
    @Scheduled(fixedDelayString = "${llmall.message.retry-interval-ms:15000}")
    public void retryPendingMessages() {
        try {
            int success = messageLogService.retryPending(50);
            if (success > 0) {
                log.info("本地消息重试完成，本次成功投递 {} 条", success);
            }
        } catch (Exception e) {
            // 定时任务绝不能因为单次异常而终止（fixedDelay 任务抛异常后 Spring 仍会继续调度，但要留日志）
            log.error("本地消息重试任务执行异常", e);
        }
    }
}
