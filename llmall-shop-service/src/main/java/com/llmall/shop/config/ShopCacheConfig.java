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
 * 【注意】逻辑过期方案的重建必须异步：持锁线程投递任务后立即返回旧值，否则就退化成同步重建、照样阻塞请求。
 * 队列满拒绝，由详情缓存释放锁并返回旧值，不能让请求线程承担重建。
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
        // 重建过程中若触发 Feign 调用，同样需要用户上下文
        executor.setTaskDecorator(new UserContextTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
