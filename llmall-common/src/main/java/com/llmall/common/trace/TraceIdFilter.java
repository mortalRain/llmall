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
 * 请求级 traceId 入口：把上游（网关或调用方服务）传来的 {@code X-Trace-Id} 写进 MDC
 * 供本次请求的全部日志使用，并把同一个值回写响应头；没有则自行生成。
 *
 * 【注意】上游传入的值必须通过 {@code TraceContext.normalize} 的白名单校验才采信：traceId 会进日志，
 * 照抄外部值等于允许注入换行与伪日志行。
 *
 * 用 Filter 而非 Interceptor 是为了覆盖 Controller 之前的鉴权、参数解析日志；本模块只依赖
 * servlet API，故不继承 OncePerRequestFilter。
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
            // 线程池复用：不清理会把 traceId 带到下一个请求上，造成串号
            TraceContext.clear();
        }
    }
}
