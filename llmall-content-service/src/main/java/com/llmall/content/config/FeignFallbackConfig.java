package com.llmall.content.config;

import com.llmall.api.fallback.ShopClientFallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为内容域声明 ShopClient 的降级工厂 Bean。
 *
 * 【注意】@EnableFeignClients 只注册 Feign 接口，不会扫描 com.llmall.api.fallback；启用 Sentinel 时
 * fallbackFactory 必须是 Bean，否则启动阶段直接抛 IllegalStateException: No fallbackFactory instance ... found。
 * llmall-api 的自动配置也注册同类型 Bean，@ConditionalOnMissingBean 保证两者不冲突。
 */
@Configuration
public class FeignFallbackConfig {

    @Bean
    @ConditionalOnMissingBean
    public ShopClientFallback shopClientFallback() {
        return new ShopClientFallback();
    }
}