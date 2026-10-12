package com.llmall.shop.config;

import com.llmall.common.concurrent.UserContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 缓存重建线程池。
 *
 * 逻辑过期方案里，缓存重建必须**异步**：持锁线程把任务丢进线程池后立刻返回旧数据，
 * 否则"异步重建"就退化成了"同步重建"，还是会阻塞用户请求。
 * 队列满时用 CallerRunsPolicy，宁可让调用线程兜底执行，也不静默丢弃重建任务。
 */
@Configuration
@EnableAsync
public class ShopCacheConfig {

    @Bean("cacheRebuildExecutor")
    public Executor cacheRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("shop-cache-rebuild-");
        // 重建过程中若触发 Feign 调用，同样需要用户上下文（顺带复用共享的装饰器）
        executor.setTaskDecorator(new UserContextTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
