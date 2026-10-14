package com.llmall.search.config;

import com.llmall.api.fallback.ShopClientFallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明 ShopClient 的降级工厂 Bean。
 *
 * 【注意】@EnableFeignClients 只注册 Feign 接口、不组件扫描 com.llmall.api.fallback，
 * 而启用 Sentinel 时 fallbackFactory 必须是 Bean，缺了会直接启动失败；
 * @ConditionalOnMissingBean 保证与 llmall-api 里的自动配置不冲突。
 */
@Configuration
public class FeignFallbackConfig {

    @Bean
    @ConditionalOnMissingBean
    public ShopClientFallback shopClientFallback() {
        return new ShopClientFallback();
    }
}