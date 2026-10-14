package com.llmall.api.config;

import com.llmall.api.fallback.ItemClientFallback;
import com.llmall.api.fallback.PayClientFallback;
import com.llmall.api.fallback.ShopClientFallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 降级工厂的自动配置，经 spring.factories 自动装配，依赖 llmall-api 的服务无需各自写配置类。
 *
 * 【注意】@EnableFeignClients 只注册被 @FeignClient 标注的接口，不会组件扫描 com.llmall.api.fallback；
 * 降级工厂未注册为 Bean 时，启用熔断的服务会在启动阶段抛 No fallbackFactory instance 异常而启动失败。
 * 该问题只在被注入的客户端第一次真正创建时暴露，编译期看不出来。
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
