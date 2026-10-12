package com.llmall.search.config;

import com.llmall.api.fallback.ShopClientFallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明 ShopClient 的降级工厂 Bean。
 *
 * 背景：@EnableFeignClients(basePackages = "com.llmall.api.client") 只注册 Feign 接口，
 * 不会组件扫描 com.llmall.api.fallback；而启用 Sentinel 时 Feign 要求 fallbackFactory
 * 必须是一个 Bean，否则启动阶段直接抛
 *   IllegalStateException: No fallbackFactory instance of type ... found for feign client ...
 * 服务自己声明一次最省事、也最确定（llmall-api 里另有自动配置兜底，@ConditionalOnMissingBean 不会冲突）。
 */
@Configuration
public class FeignFallbackConfig {

    @Bean
    @ConditionalOnMissingBean
    public ShopClientFallback shopClientFallback() {
        return new ShopClientFallback();
    }
}