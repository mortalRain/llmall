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
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 网关统一鉴权入口：外部流量的唯一身份裁决点，先剥离伪造头，再校验令牌，最后把可信身份注入下游。
 *
 * 【核心职责】
 * 1. 剥离：无条件删除客户端自带的 user-info / x-internal-token / x-service-token，下游只信网关写入的值。
 * 2. 放行判定：按"路径 + HTTP 方法"两个维度决定是否免鉴权，只读白名单只对安全方法生效（见 {@link #isExclude}）。
 * 3. 令牌校验：验签、令牌类型隔离（access 与 refresh 不得混用）、黑名单（主动注销/踢人）、
 *    会话失效水位（口令变更后旧令牌全量失效）。
 * 4. 无感刷新：access 过期但 refresh 有效时，网关本地签发新 access，经响应头 {@code x-access-token} 回给客户端。
 * 5. 注入：把可信 userId 写入 {@code user-info}，并带上内部凭证 {@code x-internal-token} 后转给下游。
 *
 * 【鉴权顺序】固定四步，顺序即安全边界，每步各自失败时的响应如下：
 * 1. 放行白名单判定：命中即按"可选身份"匿名放行，本步不产生 401（见 {@link #forwardOptionalIdentity}）；
 * 2. 令牌解析：未带令牌 → not-login；已过期 → 先试无感刷新，刷新不成 → token-expired；
 *    验签失败或缺少必需 claim → invalid-token；
 * 3. 状态校验：类型不是 access → wrong-token-type；jti 命中黑名单 → revoked；
 *    iat 早于会话失效水位 → session-invalidated；
 * 4. 注入用户头：只有前三步全部通过，才写入 user-info 与 x-internal-token 后放行下游。
 * 原因码的字面量与完整触发条件见 {@link #unauthorized}。
 *
 * 【严禁】把第 3 步提到第 2 步之前：状态校验要拿令牌里的 jti 与 userId 去查 Redis，
 * 若先用未验签的载荷去查，等于把攻击者可控的字符串直接送进 Redis 查询 ——
 * 既让"某个 jti 是否已被注销"变成可探测的信息，也把无关 key 的查询放大面交给了外部。
 * 同理第 4 步必须排在状态校验之后，否则改密后未被水位拦下的旧令牌仍能换到可信 userId 进入业务。
 *
 * 【设计意图】两条互补的失效机制：黑名单按 jti 索引，回答"这个令牌被注销了吗"；会话失效水位按 userId 索引，
 * 回答"这个用户改密之后签发的令牌才算数吗"。缺任一条都会留下窗口（只注销当前令牌则其它设备仍在线；
 * 只做水位则单令牌注销无抓手）。
 *
 * 【注意】本类是 Spring Cloud Gateway 的 {@link GlobalFilter}，对每一个路由到网关的请求生效；
 * 网关是外部流量的唯一入口，因此本类的判断结果直接等于"这个请求能不能进业务"。
 * 执行顺序 0（见 {@link #getOrder()}），排在 {@link TraceGlobalFilter} 之后，使 401 的日志也带 traceId。
 *
 * 【注意】Redis 异常时黑名单与会话失效水位的校验都按放行处理（fail-open，见 {@link #checkAuthState}）：
 * 这是可用性取舍 —— 该窗口内已注销 / 被踢下线的令牌仍能通过网关，且降级只有 ERROR 日志、没有告警指标。
 * 与之相反，白名单未配置、令牌缺 jti、脚本无返回值这些分支一律 fail-closed，两类口径不得混为一谈。
 *
 * 【注意】以下内容是既有实现的不变量，改动等于破坏功能：
 * 1. {@code x-auth-error} 的原因码字面量与 401 响应体结构是对外契约，改一个字母前端就认不出来；
 * 2. 请求头名取值来自 {@link AuthConstants}，改了下游取不到身份，表现为"用户莫名丢失"；
 * 3. {@link #AUTH_CHECK_SCRIPT} 的脚本体与 {@link #NO_REFRESH_KEY} 哨兵 key：脚本返回值语义（1 / 0 / -1）
 *    被主流程与刷新流程共用，改语义会让两条链路一起错。
 *
 * @author 同城生活
 * @since 2026-01-15
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
     * 【注意】主流程只查黑名单时给 {@code KEYS[2]} 传一个永不存在的哨兵 key（{@link #NO_REFRESH_KEY}）：
     * "refresh 不存在 → 返回 0"恰好等价于"在黑名单里"。这个等价关系依赖"哨兵 key 永远不存在"这一前提，
     * 若把哨兵 key 改成可能被写入的真实 key，主流程会把"refresh 有效"误判为"未命中黑名单"而放行已注销的令牌。
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

    /** 主流程 KEYS[2] 用的哨兵 key：带 {@code \0} 前缀、按命名约定不可能被业务写入，保证恒不存在 */
    private static final String NO_REFRESH_KEY = "auth:\u0000no-such-key";

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
     * 【异常】本方法不向外抛异常：所有失败路径都收敛为 {@link #unauthorized} 的 401 + 原因码，
     * 避免异常冒泡到网关默认处理器而丢掉 {@code x-auth-error}。
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
        });
    }

    /**
     * 校验该令牌是否已被"全量会话失效水位"覆盖（口令变更 / 账号冻结 / 管理员踢人）。
     *
     * 【判定规则】令牌签发时间（iat）早于水位 {@code auth:logoutat:{userId}} → 失效。
     * 用"签发时间"而不是"逐条拉黑"：黑名单按 jti 索引，而服务端不记录每个用户已签发的全部 access jti
     * （access 不落白名单，只记 refresh），逐条拉黑不可行；一个用户维度的时间水位即可覆盖全部旧令牌。
     *
     * 【边界约束】下面两条都是刻意的取舍，不是漏判，不得当成缺陷"修掉"：
     * 1. 兼容性取舍：旧版本签发的令牌没有 iat（{@code issuedAt == null}）或没有 userId 时跳过校验并放行，
     *    而不是拒绝 —— 否则灰度发布瞬间全站在用令牌会集体失效，表现为"所有用户被强制登出"。
     *    代价：这些旧令牌无法被水位机制召回，最迟在自身剩余寿命内自然过期（access 寿命由 llmall.jwt.tokenTTL 决定），
     *    即"改密后一段时间内、无 iat 的旧令牌仍可用"。
     * 2. 可用性取舍：Redis 异常或水位值不是时间戳时放行并打日志（fail-open，代价与风险窗口见 {@link #checkAuthState}）——
     *    期间已改密用户的旧令牌仍可通过网关，但整站不会因 Redis 抖动而全部 401。
     *
     * @param payload 已通过验签与类型校验的 access 令牌载荷；{@code userId} 为 {@code null} 时直接按"未失效"返回
     *                （宁可放行也不因缺字段误杀在线用户，缺字段的令牌在解析阶段已被拒绝）
     * @return {@code true} 表示已被水位覆盖（调用方应返回 401 且原因码 {@code session-invalidated}）；
     *         {@code false} 表示未失效、或无法判定（无 iat / Redis 异常）时的保守放行
     */
    private Mono<Boolean> isSessionInvalidated(TokenPayload payload) {
        Date issuedAt = payload.getIssuedAt();
        if (issuedAt == null || payload.getUserId() == null) {
            return Mono.just(Boolean.FALSE);
        }
        return redisTemplate.opsForValue()
                .get(AuthConstants.REDIS_GLOBAL_LOGOUT_AT + payload.getUserId())
                .map(at -> {
                    try {
                        return Long.parseLong(at) > issuedAt.getTime();
                    } catch (NumberFormatException e) {
                        // 值不是时间戳：无法判定，按"未失效"处理并告警（宁可放行也不误杀在线用户）
                        log.warn("会话失效水位值非法，按未失效处理: userId={}, value={}", payload.getUserId(), at);
                        return Boolean.FALSE;
                    }
                })
                // 水位 key 不存在（该用户从未改密）= 未失效；这正是绝大多数请求的分支
                .defaultIfEmpty(Boolean.FALSE)
                .onErrorResume(e -> {
                    // 【鉴权降级 · fail-open】Redis 异常时放行。风险窗口 = Redis 故障持续期：
                    // 已改密/被冻结用户的旧令牌在窗口内仍可通过网关；收益是整站不会因 Redis 抖动全线 401。
                    // 这里只打 ERROR 日志：本类没有上报任何降级指标，也没有配套告警规则，
                    // "降级正在发生"只能靠检索这条日志发现，禁止改成静默返回或降级为 WARN。
                    log.error("【鉴权降级】查询会话失效水位失败，本次请求按未失效放行: userId={}", payload.getUserId(), e);
                    return Mono.just(Boolean.FALSE);
                });
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
                    String newAccessToken = jwtTool.createToken(
                            payload.getUserId(), IdUtil.fastSimpleUUID(),
                            AuthConstants.TOKEN_TYPE_ACCESS, jwtProperties.getTokenTTL());
                    // 把新 access 经响应头 x-access-token 回给客户端（拒绝分支由 unauthorized 写 x-auth-error）。
                    //    用 add 而非 set：即使上游已写过同名头，也不覆盖客户端可能已放置的值造成误读
                    exchange.getResponse().getHeaders().add(AuthConstants.HEADER_NEW_ACCESS_TOKEN, newAccessToken);
                    log.info("access token 已自动刷新, userId={}", payload.getUserId());
                    return forward(exchange, chain, payload.getUserId());
                });
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
        return isBlacklisted(payload).flatMap(blacklisted ->
                Boolean.TRUE.equals(blacklisted)
                        ? chain.filter(exchange)
                        : forward(exchange, chain, payload.getUserId()));
    }

    /**
     * 校验黑名单，并可选地同时校验 refresh 白名单，一次 Redis 往返完成。
     *
     * 【设计意图】把两条判定合并进一条 Lua（原子性理由见 {@link #AUTH_CHECK_SCRIPT}）：省一次网络往返，
     * 且不会出现"查黑名单时未注销、查白名单时已作废"的中间态。
     *
     * 【fail 策略】本方法里两种"拿不到结论"的口径相反，不得混为一谈：
     * 1. Redis 异常（onErrorResume）是 fail-open：返回 1 放行并打 ERROR。收益是整站不会因 Redis 抖动全线不可用
     *    （连未登录的浏览请求都进不来）；代价是故障窗口内已注销/被踢的令牌仍能通过网关，
     *    即"提前失效"的能力在窗口内暂时失效（令牌本身仍在自身有效期内，不是新增长期风险）。
     *    本类只打日志，没有降级指标也没有告警规则，ERROR 日志是发现这个窗口的唯一途径。
     * 2. 脚本无返回值（defaultIfEmpty）是 fail-closed：按 0 拒绝。它不代表 Redis 故障，而属异常输入，宁严不宽。
     *
     * @param jti            令牌唯一编号；为 {@code null} 时黑名单 key 换成永不存在的哨兵值，使结论只取决于 refresh 判定
     * @param refreshKey     refresh 白名单 key；主流程查黑名单时传 {@link #NO_REFRESH_KEY} 哨兵
     * @param expectedUserId 期望的 userId 字符串，与 refresh 白名单 value 比对；主流程传 {@code "0"}（哨兵 key 恒不存在，该参数不参与判定）
     * @return {@code 1} = 校验通过；{@code 0} = 黑名单命中或 refresh 无效；{@code -1} = refresh 存在但 userId 不匹配
     */
    private Mono<Long> checkAuthState(String jti, String refreshKey, String expectedUserId) {
        // jti 为空时把黑名单 key 设成永不存在的值，使脚本结论只取决于 refresh 判定
        String blacklistKey = AuthConstants.REDIS_BLACKLIST + (jti == null ? "\u0000-no-jti" : jti);
        return redisTemplate.execute(AUTH_CHECK_SCRIPT,
                        Arrays.asList(blacklistKey, refreshKey),
                        Collections.singletonList(expectedUserId))
                .next()
                .map(Long::valueOf)
                // 脚本没有返回值（理论不会发生）按 0 处理，即"判定失败按拒绝"；与下面的 Redis 异常分支口径相反，
                // 无返回值不等于 Redis 故障，属异常输入，宁严不宽
                .defaultIfEmpty(0L)
                .onErrorResume(e -> {
                    // 【鉴权降级 · fail-open】见方法注释的【fail 策略】；返回 1 = 放行
                    log.error("【鉴权降级】查询令牌黑名单/白名单失败，本次请求按已登录放行: jti={}", jti, e);
                    return Mono.just(1L);
                });
    }

    /** 黑名单查询，复用 {@link #AUTH_CHECK_SCRIPT}（refresh 侧传哨兵 key）；Redis 异常时的放行取舍见 checkAuthState */
    private Mono<Boolean> isBlacklisted(TokenPayload payload) {
        if (payload.getJti() == null) {
            // 令牌缺 jti 时无法被主动失效（黑名单按 jti 索引）。解析阶段已拒绝缺 jti 的令牌，本分支是防御性兜底：
            // 一旦走到这里说明解析侧口径被放松了，按拒绝（fail-closed）比放行安全
            log.warn("access token 缺少 jti，无法校验黑名单，按拒绝处理: userId={}", payload.getUserId());
            return Mono.just(Boolean.TRUE);
        }
        // 只判"该 jti 是否在吊销黑名单里"。不能借道 refresh 白名单（jti 不同源，理由见 BLACKLIST_CHECK_SCRIPT 注释）
        String blacklistKey = AuthConstants.REDIS_BLACKLIST + payload.getJti();
        return redisTemplate.execute(BLACKLIST_CHECK_SCRIPT, Collections.singletonList(blacklistKey))
                .next()
                .map(Long::valueOf)
                // 脚本返回 0 = 命中黑名单（注销/踢人后立即失效），其余按"未吊销"处理
                .map(code -> code != null && code == 0L)
                .defaultIfEmpty(Boolean.FALSE)
                .onErrorResume(e -> {
                    // 【鉴权降级 · fail-open】口径与 checkAuthState 一致：Redis 故障不整站 401
                    log.error("【鉴权降级】查询令牌黑名单失败，本次请求按未命中黑名单放行: jti={}", payload.getJti(), e);
                    return Mono.just(Boolean.FALSE);
                });
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
