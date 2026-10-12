package com.llmall.user.config;

import com.llmall.common.concurrent.UserContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池：让 @Async 任务也能拿到当前登录用户。
 *
 * 为什么必须配：UserContext 基于 ThreadLocal，异步任务在新线程里执行，
 * 如果不做传递，异步逻辑里 UserContext.getUser() 恒为 null，
 * 表现为"异步扣积分/写日志时 user_id 为空"——一个非常隐蔽的 bug。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("userTaskExecutor")
    public Executor userTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("user-async-");
        // 关键：把调用线程的用户上下文传递进异步线程
        executor.setTaskDecorator(new UserContextTaskDecorator());
        // 队列满时由调用线程执行，避免任务被静默丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
