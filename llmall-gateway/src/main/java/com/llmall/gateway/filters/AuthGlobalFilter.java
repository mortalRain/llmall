package com.llmall.gateway.filters;

import cn.hutool.core.util.IdUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.TokenExpiredException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.gateway.config.AuthProperties;
import com.llmall.gateway.config.JwtProperties;
import com.llmall.gateway.util.JwtTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 统一鉴权过滤器（网关是唯一验签入口）。五项增强：①入口剥离客户端伪造头；②access 过期用 refresh 无感换发；
 * ③校验黑名单（注销/踢人立即失效）；④只认 access 类型令牌；⑤白名单结合 HTTP 方法判断（见 isExclude）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** "安全方法"：不改变服务端状态的 GET/HEAD/OPTIONS，只有这类才吃只读白名单，写方法一律必须带令牌 */
    private static final Set<HttpMethod> SAFE_METHODS =
            EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final JwtTool jwtTool;
    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    // 内部调用凭证（llmall.internal.token），透传身份时写给下游；为何只能由网关注入，详见 UserInfoInterceptor 的类注释
    @Value("${llmall.internal.token:}")
    private String internalToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // ① 剥离外部伪造头：user-info（防冒充他人）、x-internal-token（第 ④ 步会重写成网关自己的值）、
        //    x-service-token（服务间凭证，只剥离绝不注入）全部先删，下游只会收到网关写入的可信值。
        ServerWebExchange sanitized = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.remove(AuthConstants.HEADER_USER_INFO);
                    headers.remove(AuthConstants.HEADER_INTERNAL_TOKEN);
                    headers.remove(AuthConstants.HEADER_SERVICE_TOKEN);
                }))
                .build();
        ServerHttpRequest request = sanitized.getRequest();

        // ② 白名单放行：命中免鉴权路径就直接进业务，不再校验令牌（白名单必须带方法一起判断，见 isExclude）。
        //    但"不强制登录"不等于"不认身份"：像 GET /search 这种接口，匿名能浏览、登录后要记搜索历史，
        //    如果在这里直接 return，登录用户的 userId 就丢了 —— 表现为"搜索历史永远不记录"（实测踩过）。
        //    所以这里做**可选身份**：带了有效令牌就把身份透传给下游，没带/不合法则按匿名继续，绝不 401。
        if (isExclude(request.getPath().toString(), request.getMethod())) {
            return forwardOptionalIdentity(sanitized, chain);
        }

        // ③ 取令牌并验签：没有令牌 → 401；验签失败 → 401；access 过期 → 用 refresh 无感换发，
        //    换不到才 401（换到的新 access 由第 ⑤ 步写回响应头）。
        String token = firstHeader(request, AuthConstants.HEADER_AUTHORIZATION);
        if (token == null || token.isEmpty()) {
            return unauthorized(sanitized, "未登录", "not-login");
        }

        TokenPayload payload;
        try {
            payload = jwtTool.parse(token);
        } catch (TokenExpiredException e) {
            return tryRefresh(sanitized, chain);
        } catch (UnauthorizedException e) {
            return unauthorized(sanitized, e.getMessage(), "invalid-token");
        }

        // ③ 续：令牌类型必须是 access（refresh token 不能直接访问业务接口），且不能命中黑名单
        //    （注销/踢人后立刻失效），任一不满足都是 401。
        if (!AuthConstants.TOKEN_TYPE_ACCESS.equals(payload.getType())) {
            return unauthorized(sanitized, "令牌类型不正确", "wrong-token-type");
        }

        return isBlacklisted(payload).flatMap(blacklisted -> {
            if (Boolean.TRUE.equals(blacklisted)) {
                return unauthorized(sanitized, "登录状态已失效，请重新登录", "revoked");
            }
            // ④ 注入身份：把可信 userId 写进 user-info，并带上内部凭证 x-internal-token，然后放行给下游。
            return forward(sanitized, chain, payload.getUserId());
        });
    }

    /** 无感刷新：access 过期但 refresh 有效 → 网关本地签发新 access，经响应头 x-access-token 返回，请求继续执行 */
    private Mono<Void> tryRefresh(ServerWebExchange exchange, GatewayFilterChain chain) {
        String refreshToken = firstHeader(exchange.getRequest(), AuthConstants.HEADER_REFRESH_TOKEN);
        if (refreshToken == null || refreshToken.isEmpty()) {
            return unauthorized(exchange, "登录已过期，请重新登录", "token-expired");
        }
        TokenPayload payload;
        try {
            payload = jwtTool.parse(refreshToken);
        } catch (Exception e) {
            return unauthorized(exchange, "登录已过期，请重新登录", "refresh-invalid");
        }
        if (!AuthConstants.TOKEN_TYPE_REFRESH.equals(payload.getType())) {
            return unauthorized(exchange, "非法的刷新令牌", "wrong-token-type");
        }
        // refresh 走白名单校验（Redis 里有且 userId 一致才算有效），支持服务端主动作废
        String key = AuthConstants.REDIS_REFRESH + payload.getJti();
        return redisTemplate.opsForValue().get(key)
                .defaultIfEmpty("")
                .onErrorResume(e -> {
                    log.error("校验 refresh token 时 Redis 异常", e);
                    return Mono.just("");
                })
                .flatMap(cachedUserId -> {
                    if (!String.valueOf(payload.getUserId()).equals(cachedUserId)) {
                        return unauthorized(exchange, "刷新令牌已失效，请重新登录", "refresh-revoked");
                    }
                    String newAccessToken = jwtTool.createToken(
                            payload.getUserId(), IdUtil.fastSimpleUUID(),
                            AuthConstants.TOKEN_TYPE_ACCESS, jwtProperties.getTokenTTL());
                    // ⑤ 写响应头：把新 access 通过 x-access-token 回给客户端（拒绝时由 unauthorized 写 x-auth-error）
                    exchange.getResponse().getHeaders().add(AuthConstants.HEADER_NEW_ACCESS_TOKEN, newAccessToken);
                    log.info("access token 已自动刷新, userId={}", payload.getUserId());
                    return forward(exchange, chain, payload.getUserId());
                });
    }

    /**
     * 白名单路径的**可选身份**透传：验签通过就注入身份，否则按匿名放行。
     *
     * <p>刻意不复用主流程的失败分支：这里任何异常（令牌过期/类型不对/Redis 抖动）都只能退化成"匿名"，
     * <b>绝不能</b>变成 401 —— 否则白名单就形同虚设，未登录用户会被莫名拒绝，而登录用户又拿不到身份。
     */
    private Mono<Void> forwardOptionalIdentity(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = firstHeader(exchange.getRequest(), AuthConstants.HEADER_AUTHORIZATION);
        if (token == null || token.isEmpty()) {
            return chain.filter(exchange);
        }
        TokenPayload payload;
        try {
            payload = jwtTool.parse(token);
        } catch (Exception e) {
            return chain.filter(exchange);
        }
        if (!AuthConstants.TOKEN_TYPE_ACCESS.equals(payload.getType())) {
            return chain.filter(exchange);
        }
        return isBlacklisted(payload).flatMap(blacklisted ->
                Boolean.TRUE.equals(blacklisted)
                        ? chain.filter(exchange)
                        : forward(exchange, chain, payload.getUserId()));
    }

    /** 黑名单查询。Redis 异常时放行（可用性优先），并打错误日志告警 */
    private Mono<Boolean> isBlacklisted(TokenPayload payload) {        if (payload.getJti() == null) {
            return Mono.just(false);
        }
        return redisTemplate.hasKey(AuthConstants.REDIS_BLACKLIST + payload.getJti())
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    // 取舍：Redis 不可用时"全部拒绝"会让整站不可用，这里选择放行 + 告警。
                    log.error("查询令牌黑名单失败，本次放行", e);
                    return Mono.just(false);
                });
    }

    /** 透传用户身份给下游：写入 user-info 与内部调用凭证 x-internal-token（原因详见 UserInfoInterceptor 的类注释） */
    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, Long userId) {
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder
                        .header(AuthConstants.HEADER_USER_INFO, String.valueOf(userId))
                        .header(AuthConstants.HEADER_INTERNAL_TOKEN, internalToken))
                .build();
        return chain.filter(mutated);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setRawStatusCode(401);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        // ⑤ 写响应头（拒绝分支）：英文原因码放 x-auth-error 供前端判断是否跳登录页，中文消息放 body
        response.getHeaders().add("x-auth-error", reason);
        byte[] body = ("{\"code\":401,\"msg\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private String firstHeader(ServerHttpRequest request, String name) {
        List<String> values = request.getHeaders().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * 是否免鉴权放行。
     * 【设计原则】路径白名单必须结合 HTTP 方法判断：AntPathMatcher 的 {@code /**} 会匹配任意多段路径，
     * 而路径本身看不出读写意图 —— 白名单写一条 {@code /items/**}（本意"商品列表免登录可浏览"），
     * 就连 {@code DELETE /items/{id}}、{@code PUT /items}（改价）一起放行了，未登录就能删商品、改价格。
     * 规则：① 安全方法（GET/HEAD/OPTIONS）才走 excludePaths；② 写方法只认显式登记的 unsafeExcludePaths
     * （登录、短信验证码这类天生是 POST 的公开接口）；③ 其余写方法走正常鉴权，缺令牌即 401。
     */
    private boolean isExclude(String antPath, HttpMethod method) {
        List<String> patterns;
        if (SAFE_METHODS.contains(method)) {
            patterns = authProperties.getExcludePaths();
        } else {
            // 非安全方法：只比对显式的"公开写接口"清单，绝不使用只读白名单
            patterns = authProperties.getUnsafeExcludePaths();
        }
        if (patterns == null) {
            return false;
        }
        for (String pathPattern : patterns) {
            if (antPathMatcher.match(pathPattern, antPath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
