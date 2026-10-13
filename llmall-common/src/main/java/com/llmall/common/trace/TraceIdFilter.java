package com.llmall.common.trace;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 服务侧的 traceId 入口：把上游（网关或调用方服务）带来的 {@code X-Trace-Id}
 * 放进 MDC，供本次请求的所有日志使用；响应头回写同一个值。
 *
 * <p>【为什么是 Filter 而不是 Interceptor】Interceptor 只包住 Controller 的调用，
 * 而"请求进了哪个服务、被哪个过滤器挡下"这类日志发生在更早的阶段。
 * Filter 在整条 servlet 链之外，能覆盖包括鉴权/参数解析在内的全部日志。
 *
 * <p>【为什么不用 OncePerRequestFilter】llmall-common 里 spring-web 是 provided 的传递依赖
 * （不进编译类路径，见 pom 里 jackson-databind 的同类坑），所以这里只依赖 servlet API。
 * Filter 只需保证 doFilter 里 MDC 成对 set/clear，不会重复创建 traceId（同一个 traceId 重复 set 也无副作用）。
 */
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = TraceContext.generate();
        if (request instanceof HttpServletRequest) {
            String fromUpstream = TraceContext.normalize(((HttpServletRequest) request).getHeader(TraceContext.HEADER));
            if (fromUpstream != null) {
                traceId = fromUpstream;
            }
        }
        TraceContext.set(traceId);
        try {
            if (response instanceof HttpServletResponse) {
                ((HttpServletResponse) response).setHeader(TraceContext.HEADER, traceId);
            }
            chain.doFilter(request, response);
        } finally {
            // 线程池复用：不清理就会出现"下一个请求的日志里带着上一个请求的 traceId"（比没有还难查）
            TraceContext.clear();
        }
    }
}
