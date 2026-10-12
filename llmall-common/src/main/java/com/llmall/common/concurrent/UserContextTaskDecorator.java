package com.llmall.common.concurrent;

import com.llmall.common.utils.UserContext;
import org.springframework.core.task.TaskDecorator;

/**
 * 把当前线程的登录用户上下文传递到异步线程。
 *
 * 为什么需要它：UserContext 是 ThreadLocal，只对当前线程可见。
 * 一旦业务里用了 @Async、线程池、CompletableFuture，异步线程里 UserContext.getUser() 就是 null，
 * 于是"Feign 透传用户"在异步场景下静默失效——表现为下游服务拿不到用户、或写入的数据丢了 user_id。
 * 这是 ThreadLocal + 异步组合的经典坑，TaskDecorator 是标准解法。
 *
 * 用法（见 llmall-user-service 的 AsyncConfig）：
 * <pre>
 * ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 * executor.setTaskDecorator(new UserContextTaskDecorator());
 * </pre>
 */
public class UserContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 在"提交任务的线程"里捕获用户，注意必须在提交时取，不能在执行时取
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
