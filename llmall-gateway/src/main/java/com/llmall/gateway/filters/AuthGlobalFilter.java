package com.llmall.gateway.filters;

import cn.hutool.core.util.IdUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.TokenExpiredException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.gateway.config.AuthProperties;
import com.llmall.common.security.JwtProperties;
import com.llmall.common.security.JwtTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 网关统一鉴权入口：剥离客户端伪造的身份头、校验 access 令牌与黑名单、access 过期时用 refresh 无感换发，
 * 并把可信 userId 与内部凭证 x-internal-token 注入下游。写方法不走只读白名单，判据见 isExclude。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 安全方法：只有不改变服务端状态的 GET/HEAD/OPTIONS 才吃只读白名单，写方法一律必须带令牌 */
    private static final Set<HttpMethod> SAFE_METHODS =
            EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final JwtTool jwtTool;
    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    /**
     * 一次 Redis 往返同时完成黑名单查询与 refresh 白名单校验。KEYS[1]=黑名单 key，KEYS[2]=refresh key，ARGV[1]=userId 字符串。
     * 返回值：1=校验通过；0=黑名单命中（jti 已被注销）或 refresh key 不存在（过期/已轮换）；-1=refresh 存在但与 userId 不匹配（疑似令牌被拼到别人身上）。
     *
     * 【注意】主流程只查黑名单时传一个永不存在的 refresh key，此时返回 0 恰好等价于"在黑名单里"，无需维护第二个脚本。
     * 脚本只做只读校验、不消费 refresh：轮换由用户服务的显式刷新接口负责（那里是原子的 compare-and-delete），
     * 网关若在此删除 refresh，一旦响应丢失客户端会直接掉线。
     */
    private static final DefaultRedisScript<Long> AUTH_CHECK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                    + "local v = redis.call('get', KEYS[2]) "
                    + "if not v then return 0 end "
                    + "if v ~= ARGV[1] then return -1 end "
                    + "return 1",
            Long.class);

    /** 主流程 KEYS[2] 用的哨兵 key：带 {@code \0} 前缀、按命名约定不可能被业务写入，保证恒不存在 */
    private static final String NO_REFRESH_KEY = "auth:\u0000no-such-key";

    // 内部调用凭证（llmall.internal.token），透传身份时写给下游；只能由网关注入的原因见 UserInfoInterceptor 类注释
    @Value("${llmall.internal.token:}")
    private String internalToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // ① 剥离外部伪造头（下游只信网关写入的值）：user-info 防冒充他人、x-internal-token 第 ④ 步由网关重写、
        //    x-service-token 是服务间凭证，只剥离绝不注入
        ServerWebExchange sanitized = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.remove(AuthConstants.HEADER_USER_INFO);
                    headers.remove(AuthConstants.HEADER_INTERNAL_TOKEN);
                    headers.remove(AuthConstants.HEADER_SERVICE_TOKEN);
                }))
                .build();
        ServerHttpRequest request = sanitized.getRequest();

        // ② 白名单放行：不校验令牌，但仍做可选身份透传 —— 带了有效令牌就注入 userId，没带/不合法按匿名继续，绝不 401。
        //    不能直接 return：像 GET /search 这类接口登录后要记搜索历史，丢了 userId 就是"搜索历史永远不记录"。
        if (isExclude(request.getPath().toString(), request.getMethod())) {
            return forwardOptionalIdentity(sanitized, chain);
        }

        // ③ 取令牌并验签：缺令牌/验签失败 → 401；access 过期 → 用 refresh 无感换发，换不到才 401
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

        // ③ 续：令牌类型必须是 access（refresh token 不能直接访问业务接口）；命中黑名单（注销/踢人后立即失效）即 401
        if (!AuthConstants.TOKEN_TYPE_ACCESS.equals(payload.getType())) {
            return unauthorized(sanitized, "令牌类型不正确", "wrong-token-type");
        }

        return isBlacklisted(payload).flatMap(blacklisted -> {
            if (Boolean.TRUE.equals(blacklisted)) {
                return unauthorized(sanitized, "登录状态已失效，请重新登录", "revoked");
            }
            // ④ 注入身份：把可信 userId 写进 user-info 并带上内部凭证 x-internal-token，然后放行下游
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
        // refresh 须在 Redis 白名单中且 userId 一致才算有效（支持服务端主动作废）；与主流程共用同一脚本，一次往返取结论
        return checkAuthState(payload.getJti(), AuthConstants.REDIS_REFRESH + payload.getJti(),
                String.valueOf(payload.getUserId()))
                .flatMap(state -> {
                    if (state != 1L) {
                        if (state == -1L) {
                            log.warn("refresh token 与用户不匹配，疑似令牌拼接: userId={}, jti={}",
                                    payload.getUserId(), payload.getJti());
                        }
                        return unauthorized(exchange, "刷新令牌已失效，请重新登录", "refresh-revoked");
                    }
                    String newAccessToken = jwtTool.createToken(
                            payload.getUserId(), IdUtil.fastSimpleUUID(),
                            AuthConstants.TOKEN_TYPE_ACCESS, jwtProperties.getTokenTTL());
                    // ⑤ 把新 access 经响应头 x-access-token 回给客户端（拒绝分支由 unauthorized 写 x-auth-error）
                    exchange.getResponse().getHeaders().add(AuthConstants.HEADER_NEW_ACCESS_TOKEN, newAccessToken);
                    log.info("access token 已自动刷新, userId={}", payload.getUserId());
                    return forward(exchange, chain, payload.getUserId());
                });
    }

    /**
     * 白名单路径的可选身份透传：验签通过则注入身份，否则按匿名放行。
     *
     * 【注意】任何异常（令牌过期/类型不对/Redis 抖动）都只能退化成匿名，绝不能变成 401，
     * 否则白名单形同虚设，未登录用户被莫名拒绝、登录用户又拿不到身份。
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
        // 缺 jti 时按匿名放行：不能复用 isBlacklisted 的"缺 jti 即拒绝"分支，白名单路径的判定失败不得变成拒绝
        if (payload.getJti() == null) {
            return chain.filter(exchange);
        }
        return isBlacklisted(payload).flatMap(blacklisted ->
                Boolean.TRUE.equals(blacklisted)
                        ? chain.filter(exchange)
                        : forward(exchange, chain, payload.getUserId()));
    }

    /**
     * 校验黑名单，并可选地同时校验 refresh 白名单 —— 一次 Redis 往返。返回 1=通过，0=黑名单命中或 refresh 无效，-1=refresh 与用户不匹配。
     *
     * 【注意】Redis 异常时放行并打 ERROR 告警（可用性优先）：全部拒绝会让整站不可用（连未登录的浏览请求都进不来），
     * 代价大于故障窗口内极少数已注销令牌仍被接受；此处只保证故障可见，不做静默 fail-open。
     */
    private Mono<Long> checkAuthState(String jti, String refreshKey, String expectedUserId) {
        // jti 为空时把黑名单 key 设成永不存在的值，使脚本结论只取决于 refresh 判定
        String blacklistKey = AuthConstants.REDIS_BLACKLIST + (jti == null ? "\u0000-no-jti" : jti);
        return redisTemplate.execute(AUTH_CHECK_SCRIPT,
                        Arrays.asList(blacklistKey, refreshKey),
                        Collections.singletonList(expectedUserId))
                .next()
                .map(Long::valueOf)
                .defaultIfEmpty(0L)
                .onErrorResume(e -> {
                    log.error("【鉴权降级】查询令牌黑名单/白名单失败，本次请求按已登录放行: jti={}", jti, e);
                    return Mono.just(1L);
                });
    }

    /** 黑名单查询，复用 {@link #AUTH_CHECK_SCRIPT}（refresh 侧传哨兵 key）；Redis 异常时的放行取舍见 checkAuthState */
    private Mono<Boolean> isBlacklisted(TokenPayload payload) {
        if (payload.getJti() == null) {
            // 没有 jti 的令牌无法被主动失效（黑名单按 jti 索引），只可能来自旧版本签发或伪造，按拒绝处理比放行安全
            log.warn("access token 缺少 jti，无法校验黑名单，按拒绝处理: userId={}", payload.getUserId());
            return Mono.just(Boolean.TRUE);
        }
        return checkAuthState(payload.getJti(), NO_REFRESH_KEY, "0")
                // 脚本返回 0 = 命中黑名单（注销/踢人后立即失效）
                .map(code -> code != null && code == 0L)
                .defaultIfEmpty(Boolean.FALSE);
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
        // ⑤ 拒绝分支：英文原因码放 x-auth-error 供前端判断是否跳登录页，中文消息放 body
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
     *
     * 【注意】白名单必须结合 HTTP 方法判断：AntPathMatcher 的 {@code /**} 会匹配任意多段路径，仅凭路径看不出读写意图，
     * 白名单写 {@code /items/**}（本意列表免登录可浏览）会连 {@code DELETE /items/{id}}、{@code PUT /items} 改价一起放行。
     * 规则：安全方法才走 excludePaths；写方法只认显式登记的 unsafeExcludePaths（登录、短信验证码等天生 POST 的公开接口），其余一律正常鉴权。
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
