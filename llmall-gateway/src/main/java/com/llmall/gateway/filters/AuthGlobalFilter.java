package com.llmall.gateway.filters;

import cn.hutool.core.util.IdUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.TokenExpiredException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.metrics.BizMetrics;
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
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 网关身份入口：先剥离外部身份/服务凭据头，再验签、检查令牌类型及Redis撤销状态。
 * 检查成功才向下游注入user-info和内部凭据，不向外部用户注入服务间凭据。
 * Redis状态不可用时，受保护请求/续签返回503；公开路由仅匿名访问。
 * 已确认令牌失效仍返回401及既有x-auth-error；503不表示退出登录。
 * 黑名单与refresh白名单使用不同脚本，避免把access误当refresh校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 安全方法：只有不改变服务端状态的 GET/HEAD/OPTIONS 才走只读白名单，写方法一律必须带令牌 */
    private static final Set<HttpMethod> SAFE_METHODS =
            EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final JwtTool jwtTool;
    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final ReactiveStringRedisTemplate redisTemplate;
    /** 保留现有鉴权依赖故障指标，标签口径不变。 */
    private final BizMetrics metrics;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    /**
     * 一次 Redis 往返同时完成黑名单查询与 refresh 白名单校验，供主流程与刷新流程共用。
     *
     * 【为什么必须用 Lua】主流程只查黑名单，刷新流程需要"黑名单 + 白名单 + userId 一致性"三者同时成立。
     * 若拆成多条 Redis 命令，命令之间存在时间窗：查完白名单到返回给调用方之间，令牌可能刚被 logout 作废。
     * 放进单条 Lua 后，Redis 单线程串行执行，多条判定属于同一原子快照。
     *
     * 【返回值约定】{@code 1} = 校验通过；{@code 0} = 黑名单命中（jti 已被注销）或 refresh key 不存在
     * （已过期/已轮换）；{@code -1} = refresh 存在但与 userId 不匹配（疑似令牌被拼到别人身上，调用方打 WARN）。
     *
     * KEYS[1] = 黑名单 key（{@code auth:blacklist:} + jti），存在即视为已注销
     * KEYS[2] = refresh 白名单 key（{@code auth:refresh:} + jti），value 为 userId
     * ARGV[1] = 期望的 userId 字符串，与 {@code KEYS[2]} 的 value 比对
     *
     * 【注意】本脚本只用于 refresh 白名单校验（刷新流程）：它第二段判的是
     * "refresh key 是否存在"，而主流程手里只有 access token，两者的 jti 不同源。
     * 主流程的黑名单查询使用 {@link #BLACKLIST_CHECK_SCRIPT}，不要为了"省一个脚本"把两者合并。
     *
     * 【注意】脚本只做只读校验、不消费 refresh：轮换由用户服务的显式刷新接口负责（那里是原子的
     * compare-and-delete）。网关若在此删除 refresh，一旦响应在网络上丢失，客户端会直接掉线（令牌没了，
     * 且没有拿到新 access），并且无法重试。
     */
    private static final DefaultRedisScript<Long> AUTH_CHECK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                    + "local v = redis.call('get', KEYS[2]) "
                    + "if not v then return 0 end "
                    + "if v ~= ARGV[1] then return -1 end "
                    + "return 1",
            Long.class);

    /**
     * 黑名单只读校验：命中返回 0，未命中返回 1（与 AUTH_CHECK_SCRIPT 的"0 = 拒绝"口径一致）。
     *
     * 【为什么黑名单不能复用 AUTH_CHECK_SCRIPT】那条脚本是给 refresh 白名单校验用的，第二段
     * `if not v then return 0 end` 判定的是"refresh key 是否存在"。主流程手里只有 access token，
     * 它的 jti 与 refresh 的 jti 是两个不同的值（签发时各自 newJti()，见 TokenServiceImpl 的令牌对签发），
     * 而白名单 key 是 `auth:refresh:{refreshJti}` —— 所以 `auth:refresh:{accessJti}` 永远不存在。
     * 若给 KEYS[2] 传一个恒不存在的哨兵 key，脚本就会恒定走 `if not v then return 0`，
     * 而调用方把 0 判成"命中黑名单"，后果是每个 access token 都被判为已吊销、所有带鉴权的请求 401。
     * 黑名单本来就只该问"这个 jti 是否被吊销"，refresh 的存在性是另一条路径（{@link #tryRefresh}）的事。
     */
    private static final DefaultRedisScript<Long> BLACKLIST_CHECK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end return 1",
            Long.class);

    // 内部调用凭证（llmall.internal.token）：认证通过后与 user-info 一起写给下游，下游两个头同时具备才采信身份。
    // 它只能由网关注入 —— 客户端自带的同名头在入口处已被剥离，因此它是"请求经过网关"的凭证。
    // 【严禁】用它判断"这是服务间调用"：凡认证通过的用户请求都带着它，拿它判内部调用等于没判。
    // 判断服务间调用只能用 x-service-token（网关只剥离、绝不注入），见 UserInfoInterceptor 类注释。
    // 【注意】该值与所有服务（含网关自己）的 llmall.internal.token 必须完全一致：不一致时下游 UserInfoInterceptor
    // 会按"疑似绕过网关直连"处理，表现为"网关放行了、但业务侧 UserContext 取不到 userId"（所有接口都像未登录）。
    // 未配置（空串）时下游一律不采信 user-info，鉴权形同全站匿名 —— 启动前必须确认该配置项非空。
    @Value("${llmall.internal.token:}")
    private String internalToken;

    /**
     * 鉴权主流程：剥离伪造头 → 白名单放行 → 验签 → 类型校验 → 黑名单 → 会话失效水位 → 注入身份。
     *
     * 【设计意图】顺序即安全边界：类型校验必须早于黑名单查询（否则 refresh 令牌能直接访问业务接口），
     * 水位校验必须早于身份注入（否则改密后旧令牌仍能拿到新身份）；顺序被调换打开的漏洞见类注释。
     * 步骤编号与源码中的 ①~⑤ 注释一一对应。
     *
     * 【边界约束】白名单分支（第 ② 步）只能返回"匿名放行"，绝不返回 401；只有认证全通过（第 ⑤ 步）
     * 才会写入 {@code user-info} 与 {@code x-internal-token}；{@code x-service-token} 在本类中永远只被删除、绝不写入。
     *
     * 鉴权拒绝直接写响应：令牌无效返回401，鉴权依赖故障返回503；下游错误保持原样传播。
     *
     * @param exchange 当前请求交换对象（含请求头、路径、方法与响应），由网关框架传入，不为 null
     * @param chain    网关过滤器链，非白名单且鉴权通过时以变异后的请求继续向后传递
     * @return 异步完成信号 {@link Mono}；鉴权失败时已完成 401 响应写入，成功时代表下游处理完成
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // ① 剥离外部伪造头（下游只信网关写入的值）：user-info 防冒充他人、x-internal-token 由网关在认证通过后重写、
        //    x-service-token 是服务间凭证，网关只剥离绝不注入。
        //    【严禁】这三行必须无条件执行、且位于所有 return 分支之前：任何一条被跳过（例如挪进白名单分支之后、
        //    或改成"白名单路径不剥离"），外部就能自带 user-info: 1 冒充任意用户，是完整的认证绕过。
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

        // ③ 取令牌并验签：缺令牌/验签失败 → 401；access 过期（TokenExpiredException）→ 用 refresh 无感换发，换不到才 401。
        //    注意"过期"与"非法"必须是两个分支：过期可刷新，非法（签名错/缺 claim）刷新也没用，混在一起会让前端做错交互。
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

        // ③ 续：令牌类型必须是 access。refresh 的寿命由 llmall.jwt.refreshTTL 决定，比 access 长得多，
        //    放它进业务接口等于把 access 短寿命的优势抹掉；命中黑名单（注销/踢人后立即失效）即 401
        if (!AuthConstants.TOKEN_TYPE_ACCESS.equals(payload.getType())) {
            return unauthorized(sanitized, "令牌类型不正确", "wrong-token-type");
        }

        return isBlacklisted(payload).flatMap(blacklisted -> {
            if (Boolean.TRUE.equals(blacklisted)) {
                return unauthorized(sanitized, "登录状态已失效，请重新登录", "revoked");
            }
            // ④ 水位校验：口令变更（改密/冻结/踢人）后，此前签发的令牌全部失效，必须重新登录。
            //    必须在 ⑤ 注入身份之前完成，否则失效令牌仍能换到一个可信 userId 进入业务。
            return isSessionInvalidated(payload).flatMap(invalidated -> {
                if (Boolean.TRUE.equals(invalidated)) {
                    return unauthorized(sanitized, "登录状态已失效，请重新登录", "session-invalidated");
                }
                // ⑤ 注入身份：把可信 userId 写进 user-info 并带上内部凭证 x-internal-token，然后放行下游
                return forward(sanitized, chain, payload.getUserId());
            });
        }).onErrorResume(AuthStateUnavailable.class, e -> unavailable(sanitized));
    }

    /** 无水位表示尚未全量撤销；无法读取或解析水位不等于状态有效。保留无iat旧令牌兼容。 */
    private Mono<Boolean> isSessionInvalidated(TokenPayload payload) {
        Date issuedAt = payload.getIssuedAt();
        if (issuedAt == null || payload.getUserId() == null) {
            return Mono.just(Boolean.FALSE);
        }
        return Mono.defer(() -> redisTemplate.opsForValue()
                        .get(AuthConstants.REDIS_GLOBAL_LOGOUT_AT + payload.getUserId()))
                .map(at -> Long.parseLong(at) > issuedAt.getTime())
                .defaultIfEmpty(Boolean.FALSE)
                .onErrorMap(e -> stateUnavailable("watermark", e));
    }

    /**
     * 无感刷新：access 过期但 refresh 有效时，网关本地签发新 access，经响应头 x-access-token 返回，请求继续执行。
     *
     * 【设计意图】access 寿命由 llmall.jwt.tokenTTL 决定且远短于 refresh，若过期一律 401，用户会在操作到一半时被踢下线。
     * 本方法用 refresh 换发新 access，让用户在会话有效期内完全无感知。新令牌由网关本地签发（与用户服务共用 {@link JwtTool}），
     * 省掉一次跨服务调用，也让刷新不依赖用户服务可用性。
     *
     * 【边界约束】
     * 1. refresh 必须在 Redis 白名单中且 userId 一致，否则视为已轮换/已作废 —— 白名单是服务端可主动作废 refresh 的唯一抓手。
     * 2. 新 access 的 jti 用 {@code IdUtil.fastSimpleUUID()} 重新生成：绝不能复用 refresh 的 jti，
     *    否则"注销某个 access"会连带把 refresh 也拉黑，或反之。
     * 3. 本方法不轮换 refresh（不删白名单、不发新 refresh）：轮换是用户服务显式刷新接口的职责，
     *    网关在这里做会因响应丢失导致客户端掉线（令牌没了，也没拿到新 access），理由见 {@link #AUTH_CHECK_SCRIPT}。
     *
     * @param exchange    当前请求交换对象；成功时新 access 会写入其响应的 {@code x-access-token} 头
     * @param chain       网关过滤器链，刷新成功后以原请求（外加注入的身份头）继续
     * @return 刷新成功则继续执行下游；失败则为 401（原因码 {@code token-expired} / {@code refresh-invalid} /
     *         {@code wrong-token-type} / {@code refresh-revoked} 之一）
     */
    private Mono<Void> tryRefresh(ServerWebExchange exchange, GatewayFilterChain chain) {
        String refreshToken = firstHeader(exchange.getRequest(), AuthConstants.HEADER_REFRESH_TOKEN);
        if (refreshToken == null || refreshToken.isEmpty()) {
            return unauthorized(exchange, "登录已过期，请重新登录", "token-expired");
        }
        TokenPayload payload;
        try {
            // 这里用 catch (Exception)：refresh 的任何解析失败（签名错、格式错、过期、缺 claim）都只能"重新登录"，
            // 区分具体异常对前端没有额外信息量，故统一收敛为 refresh-invalid
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
                    // 脚本约定：只有 1 才是通过；0 与 -1 都是拒绝，但 -1 需要额外告警（可能有攻击行为）
                    if (state != 1L) {
                        if (state == -1L) {
                            // userId 与白名单 value 不一致：正常轮换不会产生这种组合，疑似把两个令牌拼在一起试探
                            log.warn("refresh token 与用户不匹配，疑似令牌拼接: userId={}, jti={}",
                                    payload.getUserId(), payload.getJti());
                        }
                        return unauthorized(exchange, "刷新令牌已失效，请重新登录", "refresh-revoked");
                    }
                    return isSessionInvalidated(payload).flatMap(invalidated -> {
                        if (Boolean.TRUE.equals(invalidated)) {
                            return unauthorized(exchange, "登录状态已失效，请重新登录", "session-invalidated");
                        }
                        String newAccessToken = jwtTool.createToken(payload.getUserId(), IdUtil.fastSimpleUUID(),
                                AuthConstants.TOKEN_TYPE_ACCESS, jwtProperties.getTokenTTL());
                        exchange.getResponse().getHeaders().add(AuthConstants.HEADER_NEW_ACCESS_TOKEN, newAccessToken);
                        return forward(exchange, chain, payload.getUserId());
                    });
                }).onErrorResume(AuthStateUnavailable.class, e -> unavailable(exchange));
    }

    /**
     * 白名单路径的可选身份透传：验签通过则注入身份，否则按匿名放行。
     *
     * 【设计意图】白名单只表示"不强制登录"，不等于"没有身份"。GET /search、GET /blogs 这类接口登录后需要按 userId
     * 记搜索历史、判断"我是否点过赞"，因此这里做一次"尽力而为"的解析：能解析出身份就注入，不能就匿名。
     *
     * 【边界约束】
     * 1. 任何异常（令牌过期 / 类型不对 / Redis 抖动 / 缺 jti）都只能退化成匿名，绝不能变成 401，
     *    否则白名单形同虚设：未登录用户被莫名拒绝、登录用户又拿不到身份。
     * 2. 不调 {@link #tryRefresh}：白名单路径不承担刷新职责，避免匿名路径触发签发新令牌的副作用。
     *
     * @param exchange 当前请求交换对象（此处已剥离伪造头）
     * @param chain    网关过滤器链
     * @return 恒为"继续执行下游"，仅在能确认身份时额外注入 {@code user-info} 与 {@code x-internal-token}
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
            // 过期 / 非法 / 格式错一律匿名放行：白名单路径的令牌问题不是错误，只是"没有身份"
            return chain.filter(exchange);
        }
        if (!AuthConstants.TOKEN_TYPE_ACCESS.equals(payload.getType())) {
            return chain.filter(exchange);
        }
        // 缺 jti 时按匿名放行：不能复用 isBlacklisted 的"缺 jti 即拒绝"分支，白名单路径的判定失败不得变成拒绝
        if (payload.getJti() == null) {
            return chain.filter(exchange);
        }
        // 命中黑名单（已注销）时降级为匿名，而不是 401 —— 白名单接口匿名本来就能访问，
        // 用 401 会让"刚注销的用户连商品列表都刷不出来"
        return isBlacklisted(payload).flatMap(blacklisted -> {
            if (Boolean.TRUE.equals(blacklisted)) {
                return chain.filter(exchange);
            }
            return isSessionInvalidated(payload).flatMap(invalidated -> Boolean.TRUE.equals(invalidated)
                    ? chain.filter(exchange) : forward(exchange, chain, payload.getUserId()));
        }).onErrorResume(AuthStateUnavailable.class, e -> chain.filter(exchange));
    }

    /** 刷新必须同时通过黑名单、refresh归属检查；依赖异常不作为检查成功。 */
    private Mono<Long> checkAuthState(String jti, String refreshKey, String expectedUserId) {
        String blacklistKey = AuthConstants.REDIS_BLACKLIST + (jti == null ? "\u0000-no-jti" : jti);
        return Mono.defer(() -> redisTemplate.execute(AUTH_CHECK_SCRIPT,
                        Arrays.asList(blacklistKey, refreshKey), Collections.singletonList(expectedUserId)).next())
                .defaultIfEmpty(0L)
                .onErrorMap(e -> stateUnavailable("token-state", e));
    }

    /** 仅检查access黑名单；没有脚本结果时不采信身份。 */
    private Mono<Boolean> isBlacklisted(TokenPayload payload) {
        if (payload.getJti() == null) {
            return Mono.just(Boolean.TRUE);
        }
        String blacklistKey = AuthConstants.REDIS_BLACKLIST + payload.getJti();
        return Mono.defer(() -> redisTemplate.execute(BLACKLIST_CHECK_SCRIPT,
                        Collections.singletonList(blacklistKey)).next())
                .map(code -> code != 1L)
                .defaultIfEmpty(Boolean.TRUE)
                .onErrorMap(e -> stateUnavailable("blacklist", e));
    }

    private AuthStateUnavailable stateUnavailable(String stage, Throwable cause) {
        metrics.authDegrade(stage);
        log.error("鉴权状态检查失败，暂不采信身份: stage={}, failure={}", stage, cause.getClass().getSimpleName());
        return new AuthStateUnavailable(cause);
    }

    private Mono<Void> unavailable(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setRawStatusCode(503);
        response.getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        byte[] body = "{\"code\":503,\"msg\":\"登录状态暂时无法确认，请稍后重试\"}".getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static class AuthStateUnavailable extends RuntimeException {
        AuthStateUnavailable(Throwable cause) {
            super(cause);
        }
    }

    /**
     * 透传用户身份给下游：写入 user-info 与内部调用凭证 x-internal-token（原因详见 UserInfoInterceptor 的类注释）。
     *
     * 【严禁】只写这两个头，不写 x-service-token：该头代表"服务间调用"，网关一旦按用户请求注入，
     * 等于把内部接口开放给所有登录用户（例如交易的"标记订单已支付"只认服务间凭证，注入后任何用户都能调）。
     *
     * @param exchange 已剥离伪造头的交换对象（在此之上再叠加身份头）
     * @param chain    网关过滤器链
     * @param userId   已验证的可信用户 id，写入 {@code user-info}；为 {@code null} 时下游按未登录处理
     * @return 下游处理完成的异步信号
     */
    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, Long userId) {
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder
                        .header(AuthConstants.HEADER_USER_INFO, String.valueOf(userId))
                        .header(AuthConstants.HEADER_INTERNAL_TOKEN, internalToken))
                .build();
        return chain.filter(mutated);
    }

    /**
     * 统一的鉴权拒绝出口：401 + JSON body + {@code x-auth-error} 原因码。
     *
     * 【设计意图】原因码与 HTTP 状态码分离：HTTP 层统一 401（前端、网关、监控只有一条判据），业务层用英文原因码
     * 区分细分场景，前端据此决定"跳登录页 / 清本地令牌 / 尝试 refresh"。中文 msg 面向用户展示。
     *
     * 【返回值约定】401 的响应体固定为 {"code":401,"msg":"..."}（msg 为中文提示，与原因码同属对外契约），
     * 原因码与其触发条件如下（字面量即契约，改一个字母前端就认不出来）：
     * not-login：未携带 {@code authorization} 头或为空，引导登录，重试必然再 401；
     * invalid-token：验签失败、格式非法或缺少 exp / jti / type / user 等必需 claim，刷新也无用，应清本地令牌；
     * wrong-token-type：拿 refresh 或其它类型令牌访问业务接口（刷新流程里 {@code x-refresh-token} 不是 refresh 也算），
     * 按"客户端把两种令牌存反了"处理；
     * revoked：jti 命中 Redis 黑名单（已注销 / 被踢下线），清本地令牌后引导登录；
     * session-invalidated：iat 早于会话失效水位（改密 / 冻结 / 踢人）；该用户的 refresh 同样已作废，
     * 前端不得自动重试、也不得用 refresh 续期；
     * token-expired：access 已过期，且请求没带 {@code x-refresh-token}；
     * refresh-invalid：refresh 验签失败或自身已过期（超过 {@code llmall.jwt.refreshTTL}），无法再续期；
     * refresh-revoked：refresh 不在 Redis 白名单（已轮换 / 已作废），或白名单里的 userId 与令牌不一致；
     * 轮换后旧值永久失效，不得用同一个 refresh 反复重试。
     *
     * @param exchange 当前请求交换对象，响应在此直接写入并结束（不会进入下游 Filter）
     * @param message  面向用户的中文提示，写入 body 的 {@code msg} 字段
     * @param reason   英文原因码，取值必须是上面清单中的八个之一（新增取值必须同步更新前端）
     * @return 响应写完即完成的异步信号；响应体为 UTF-8 JSON，charset 显式声明以免客户端按 GBK 解码出乱码
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        // 用 setRawStatusCode 而非 setStatusCode：不触发 Spring 的默认错误处理，保证 401 响应体就是下面这段 JSON
        response.setRawStatusCode(401);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        // 拒绝分支：英文原因码放 x-auth-error 供前端判断是否跳登录页，中文消息放 body
        response.getHeaders().add("x-auth-error", reason);
        // #request# body 手写拼接而非 JSON 序列化：message 全部来自本类的固定字面量，不含用户输入，无转义风险
        byte[] body = ("{\"code\":401,\"msg\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /**
     * 取请求头的第一个值：显式判空列表，让"头不存在"与"头为空列表"两种情形由调用方按同一口径（空）处理。
     *
     * @return 第一个头值；头缺失或值为空列表时返回 {@code null}
     */
    private String firstHeader(ServerHttpRequest request, String name) {
        List<String> values = request.getHeaders().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * 是否免鉴权放行。
     *
     * 【设计意图】白名单必须结合 HTTP 方法判断：{@link AntPathMatcher} 的 {@code /**} 会匹配任意多段路径，
     * 仅凭路径看不出读写意图 —— 白名单写 {@code /items/**}（本意"商品详情免登录可浏览"）会连
     * {@code DELETE /items/{id}}、{@code PUT /items/stock/restore}（无限加库存）、{@code PUT /items}（改价/上下架）
     * 一起放行。
     *
     * 【边界约束】
     * 1. 安全方法（GET/HEAD/OPTIONS，见 {@link #SAFE_METHODS}）才走 {@code llmall.auth.excludePaths}（只读白名单）。
     * 2. 写方法只认显式登记的 {@code llmall.auth.unsafeExcludePaths}（登录、短信验证码等天生 POST 的公开接口），
     *    其余一律正常鉴权。
     * 3. 白名单未配置（{@code null}）时恒返回 false，即 fail-closed：配置缺失的后果是"接口要求登录"，
     *    而不是"接口裸奔"。
     * 4. {@code includePaths} 在本方法中没有位置：把它当兜底判断是错的，当前实现不使用该配置项。
     *
     * 【注意】误操作后果：把写方法也拿去比对 {@code excludePaths}，等价于把只读白名单当全量白名单用，
     * 于是 {@code /items/*} 这一条会顺带放行 {@code DELETE /items/1}；这类改动不会报错、不会告警，
     * 只会表现为"某个写接口匿名可调"。
     *
     * @param antPath 请求路径（不含查询串），如 {@code /items/900013}
     * @param method  请求方法；为 {@code null} 时按非安全方法处理（走 unsafeExcludePaths，更严）
     * @return {@code true} 表示免鉴权（调用方按"可选身份"放行）；白名单为 {@code null}（未配置）时恒返回 {@code false}
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
            // 未配置 = 不放行任何路径（fail-closed）：配置缺失的后果是"接口要求登录"，而不是"接口裸奔"
            return false;
        }
        for (String pathPattern : patterns) {
            if (antPathMatcher.match(pathPattern, antPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 过滤器顺序：0。
     *
     * 【取值依据】网关内置的 {@code NettyRoutingFilter} 等路由类过滤器序号都在 {@code Integer.MAX_VALUE} 附近，
     * 鉴权取 0 即可保证"先鉴权、后路由"。同时必须大于 {@link TraceGlobalFilter} 的
     * {@code Ordered.HIGHEST_PRECEDENCE}，使 traceId 在 401/403 时已经就位。
     * 【调小后果】取到 HIGHEST_PRECEDENCE 之前会与 traceId 起点抢先后，401 日志可能不带 traceId，排障时无法关联。
     * 【调大后果】若大于路由过滤器，未鉴权请求会先被转发到下游服务（下游虽会再校验，但已产生一次无效调用与内部错误码）。
     *
     * @return 过滤器顺序值 0
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
