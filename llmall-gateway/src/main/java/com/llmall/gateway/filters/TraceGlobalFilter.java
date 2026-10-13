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
 * 全链路 traceId 的起点：进入网关的每个请求都拿到一个 traceId，并透传给下游服务。
 *
 * <p>【职责与边界】
 * <ul>
 *   <li>入口没有 {@code X-Trace-Id} → 生成；有 → 校验后沿用（见 {@link TraceContext#normalize}，
 *       不合规的值一律丢弃重生成，避免外部往日志里注入换行伪造日志行）。</li>
 *   <li>把 traceId 写进请求头传给下游 —— 下游的 TraceIdFilter 会把它放进 MDC，
 *       于是 gateway/trade/item/pay 四份日志可以用同一个 ID 捞出来。</li>
 *   <li>同时回写响应头：用户报障时报这一个值即可定位整条链路。</li>
 *   <li>本过滤器是**链路上第一个**（HIGHEST_PRECEDENCE），排在鉴权之前：
 *       这样 401/403 这类"没进业务就失败"的日志也带 traceId（鉴权失败的日志恰恰最需要它）。</li>
 * </ul>
 *
 * <p>【已知取舍】网关是 WebFlux，日志发生在 Reactor 线程上，MDC 不会自动跟着线程切换走，
 * 所以这里只保证"过滤器中打的那一条日志 + 传给下游的请求头"带 traceId；
 * 网关内部更深的 Reactor 链路日志要带 traceId 需要引入 micrometer context-propagation
 * （代价是把整条链路的线程上下文都改一遍），当前不做 —— 下游服务的日志才是排查主战场。
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
