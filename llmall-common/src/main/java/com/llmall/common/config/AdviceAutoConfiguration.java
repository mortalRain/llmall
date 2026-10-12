package com.llmall.common.config;

import com.llmall.common.advice.CommonExceptionAdvice;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 统一异常处理的自动配置入口。
 *
 * 为什么不直接把 CommonExceptionAdvice 写进 spring.factories：
 *   ① Boot 的 EnableAutoConfiguration 条目要求是 @Configuration 类，直接放一个 @RestControllerAdvice
 *      进去，条件注解不会被当作自动配置条件处理，网关（WebFlux、类路径上没有 javax.servlet）
 *      会被迫加载它并抛 NoClassDefFoundError: javax/servlet/ServletException，整个网关起不来（实测踩过）；
 *   ② 这里用 @Configuration + @Import + @ConditionalOnWebApplication(SERVLET) 显式限定：
 *      只有 Servlet 应用（真正的 9 个业务服务）才装配异常处理器，网关不受影响。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(CommonExceptionAdvice.class)
public class AdviceAutoConfiguration {
}
