package com.llmall.gateway.filters;

import com.llmall.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全链路 traceId 的起点：为进入网关的每个请求确定 traceId，并透传给下游服务。
 *
 * 【核心职责】
 * 1. 产生：请求头带来的 X-Trace-Id 先经 {@link TraceContext#normalize} 校验，合规则沿用，不合规则丢弃并重新生成；
 * 2. 透传：把最终生效的 traceId 原样写入请求头，下游服务与后续的 Feign 调用继续沿用同一个值；
 * 3. 回写：响应头回写同一个 traceId，供对外报障时定位整条链路。
 *
 * 【注意】上游带来的 X-Trace-Id 必须经 normalize 校验后才沿用，不合规的值一律丢弃重生成：
 * traceId 会进日志，照抄未经校验的请求头等于让外部用换行等内容伪造日志行。
 * 请求头用 mutate().header(...) 覆盖写入而不是追加，保证下游只会看到本过滤器确定的这一个值；
 * 若改成追加，外部可以先塞一个合规的伪造值，下游取首个值就会拿到伪造的 traceId。
 *
 * 【边界约束】本过滤器顺序为 {@code Ordered.HIGHEST_PRECEDENCE}，排在鉴权过滤器 AuthGlobalFilter 之前，
 * 使 401/403 这类未进业务的日志也带 traceId。
 * MDC 不随 Reactor 线程切换传递，故只保证本过滤器打的日志与透传给下游的请求头带 traceId。
 */
@Component
public class TraceGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TraceGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String fromUpstream = TraceContext.normalize(exchange.getRequest().getHeaders().getFirst(TraceContext.HEADER));
        final String traceId = fromUpstream != null ? fromUpstream : TraceContext.generate();
        // 只影响当前事件循环线程上"本过滤器打的这条日志"；下游靠请求头传递
        TraceContext.set(traceId);
        exchange.getResponse().getHeaders().set(TraceContext.HEADER, traceId);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(TraceContext.HEADER, traceId)
                .build();
        log.info("traceId={} {} {}", traceId,
                exchange.getRequest().getMethodValue(),
                exchange.getRequest().getURI().getRawPath());
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> TraceContext.clear());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
