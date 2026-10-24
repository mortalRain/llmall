package com.llmall.common.config;

import com.llmall.common.metrics.BizMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务指标的自动装配：只要类路径上有 Micrometer（父 pom 已统一引入），
 * 各服务就能注入 BizMetrics 而不必各自声明 Bean。
 *
 * 【注意】不加 @ConditionalOnWebApplication：网关是 WebFlux 应用，同样需要
 * 鉴权降级等指标；本类只依赖 MeterRegistry，与 Web 栈无关。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry.class)
public class MetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BizMetrics bizMetrics(MeterRegistry registry) {
        return new BizMetrics(registry);
    }
}
