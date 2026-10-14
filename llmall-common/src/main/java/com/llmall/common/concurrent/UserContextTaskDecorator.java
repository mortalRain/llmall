package com.llmall.common.concurrent;

import com.llmall.common.utils.UserContext;
import org.springframework.core.task.TaskDecorator;

/**
 * 把当前线程的登录用户上下文传递到异步线程，注册为线程池的 taskDecorator 使用（见 llmall-user-service 的 AsyncConfig）。
 *
 * 【注意】UserContext 是 ThreadLocal，只对当前线程可见；用了 @Async、线程池或 CompletableFuture 后，
 * 异步线程里 UserContext.getUser() 恒为 null，Feign 透传用户在异步场景下静默失效
 * （下游拿不到用户，或写入的数据丢了 user_id）。TaskDecorator 是标准解法。
 */
public class UserContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 必须在提交任务的线程里捕获用户：执行时已经是异步线程，取不到原上下文
        Long userId = UserContext.getUser();
        return () -> {
            try {
                if (userId != null) {
                    UserContext.setUser(userId);
                }
                runnable.run();
            } finally {
                // 线程池线程会被复用，必须清理，否则会把上一个任务的身份带给下一个任务（串号）
                UserContext.removeUser();
            }
        };
    }
}
