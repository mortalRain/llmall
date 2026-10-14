package com.llmall.common.config;
import com.llmall.common.interceptor.UserInfoInterceptor;
import com.llmall.common.trace.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnClass(DispatcherServlet.class)
public class MvcConfig implements WebMvcConfigurer {

    /**
     * 内部调用凭证（llmall.internal.token），拦截器据此判断 user-info 头是否由内部调用写入。
     * 【注意】未配置（默认空串）时拦截器一律不采信 user-info（fail-closed），
     * 否则任何能直连服务端口的人都能自带 user-info 冒充任意用户。
     */
    @Value("${llmall.internal.token:}")
    private String internalToken;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserInfoInterceptor(internalToken));
    }

    /**
     * 全链路 traceId：过滤器取最高优先级，保证鉴权、参数解析、异常处理的日志都带同一 traceId；
     * 上游（网关）已生成的值直接沿用，跨服务 Feign 调用由 DefaultFeignConfig 继续透传。
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }
}
