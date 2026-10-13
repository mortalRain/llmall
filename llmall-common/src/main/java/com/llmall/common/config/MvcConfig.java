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
     * 内部调用凭证（llmall.internal.token）。
     * 拦截器靠它判断 user-info 头是不是"我们自己人"写的 —— 见 UserInfoInterceptor 的注释：
     * 没有它，任何能直连服务端口的人都能自带 user-info 冒充任意用户。
     * 这里不设默认值（默认空串）：没配置时拦截器一律不采信 user-info（fail-closed），
     * 而不是悄悄退化成"谁都能自称任意用户"。
     */
    @Value("${llmall.internal.token:}")
    private String internalToken;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserInfoInterceptor(internalToken));
    }

    /**
     * 全链路 traceId：最先执行的 Filter（最高优先级），保证本服务本次请求的所有日志
     * （含鉴权、参数解析、异常处理）都带同一个 traceId。
     * 上游是网关时它已生成好，跨服务 Feign 调用由 DefaultFeignConfig 继续透传。
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
