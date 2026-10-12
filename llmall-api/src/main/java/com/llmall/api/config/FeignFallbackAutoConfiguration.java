package com.llmall.api.config;

import com.llmall.api.fallback.ItemClientFallback;
import com.llmall.api.fallback.PayClientFallback;
import com.llmall.api.fallback.ShopClientFallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 降级工厂的自动配置。
 *
 * 【为什么需要它】
 * 各服务的启动类只有 {@code @EnableFeignClients(basePackages = "com.llmall.api.client")}，
 * 它只注册被 {@code @FeignClient} 标注的**接口**，并**不会**组件扫描 {@code com.llmall.api.fallback}。
 * 而本项目的降级类写在那个包里（即使是 @Component 也没人扫），
 * 于是在启用 Sentinel/熔断时，Spring Cloud OpenFeign 启动阶段会直接抛：
 *   IllegalStateException: No fallbackFactory instance of type ... found for feign client ...
 * 整个服务因此启动失败 —— 这个坑在被注入的客户端第一次真正创建时才暴露，IDE 里编译完全看不出来。
 *
 * 【做法】把降级工厂声明为 Bean，并通过 spring.factories 自动装配，
 * 这样任何依赖 llmall-api 的服务都能直接拿到，不需要各自写配置类。
 */
@Configuration
public class FeignFallbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ItemClientFallback itemClientFallback() {
        return new ItemClientFallback();
    }

    @Bean
    @ConditionalOnMissingBean
    public PayClientFallback payClientFallback() {
        return new PayClientFallback();
    }

    @Bean
    @ConditionalOnMissingBean
    public ShopClientFallback shopClientFallback() {
        return new ShopClientFallback();
    }
}
