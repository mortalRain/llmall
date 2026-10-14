package com.llmall.common.config;

import com.llmall.common.advice.CommonExceptionAdvice;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 统一异常处理的自动配置入口。
 *
 * 【注意】必须由 @Configuration + @Import + @ConditionalOnWebApplication(SERVLET) 装配：
 * 直接把 CommonExceptionAdvice 写进 spring.factories 的 EnableAutoConfiguration 条目时，
 * 条件注解不按自动配置条件生效，网关（WebFlux，类路径无 javax.servlet）会被迫加载并以
 * NoClassDefFoundError 启动失败；限定 SERVLET 后只有 Servlet 应用才装配异常处理器。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(CommonExceptionAdvice.class)
public class AdviceAutoConfiguration {
}
