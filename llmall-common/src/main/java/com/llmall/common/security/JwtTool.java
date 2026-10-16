package com.llmall.common.security;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.TokenExpiredException;
import com.llmall.common.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 的签发、校验与容忍过期的解析。网关（验签、无感刷新）与用户服务（签发、轮换）共用同一份实现。
 *
 * 【核心职责】令牌生命周期四段闭环，缺任一段都会留下可利用的窗口：
 * ① 签发：createToken，强制写入 user/jti/type/exp 与 iat，jti 唯一、exp 必填；
 * ② 校验：parse，验签 → 校验 exp 存在 → 校验未过期 → 校验 jti/type/user 存在；
 * ③ 吊销：用户服务 logout 以 jti 写黑名单（TTL = 令牌剩余寿命），并作废该用户全部 refresh；
 *    过期令牌经 parseAllowExpired 仍能识别 userId，故"登出"不因 access 已过期而失效；
 * ④ 清理：黑名单 TTL 与令牌寿命严格对齐，令牌自然过期时 key 同时消失，Redis 不会无限增长。
 *
 * 【access 与 refresh 的分工】两段都是独立令牌，各带自己的 jti 与 type：
 * access 用于访问业务接口，寿命较短，它决定无感刷新的频率；
 * refresh 只用于换取新 access，不承载业务访问（parse 返回的 type 供调用方做用途隔离），
 * 寿命较长，它决定会话最长存活。寿命由调用方按 type 传入，本类不设默认值；
 * refresh 必须明显长于 access，否则无感刷新没有意义。
 * 两段 jti 不同，吊销一段不影响另一段，因此一次登出必须两段都处理：
 * 只拉黑 access 会留下"已登出但 refresh 仍能换取新 access"的窗口。
 *
 * 【异常口径】解析区分"已过期"与"非法"：过期抛 {@link TokenExpiredException}，网关据此触发无感刷新；
 * 其余问题（签名、格式、缺少必需 claim）一律按非法令牌处理。
 *
 * 【注意】两侧必须共用：签发与验签一旦各持一份实现，任何一方改动都可能让另一方签发的令牌验不过，
 * 表现为"登录成功但所有接口 401"，且只在跨服务调用时才暴露。本类集中放在 llmall-common 就是为了
 * 杜绝这种漂移；各服务在自己的 SecurityConfig 里以 @Bean 暴露本类（common 不在组件扫描范围内）。
 */
@Slf4j
public class JwtTool {

    private final JWTSigner jwtSigner;

    public JwtTool(KeyPair keyPair) {
        this.jwtSigner = JWTSignerUtil.createSigner("rs256", keyPair);
    }

    /**
     * 签发令牌。
     *
     * 【返回】签名后的 JWT 字符串；user/jti/type/exp/iat 五项全部由本方法写入，
     * 调用方只需给出 jti/type/ttl，不要自行拼 payload，否则会绕过 exp 与 iat 的必填约束。
     *
     * @param userId 用户 id
     * @param jti    令牌唯一编号，同一用户签发的每个令牌都必须不同（黑名单与 refresh 轮换都按它索引）
     * @param type   令牌类型，取 AuthConstants 中的 access / refresh
     * @param ttl    有效期，由调用方按 type 分别传入：access 短、refresh 长
     */
    public String createToken(Long userId, String jti, String type, Duration ttl) {
        return JWT.create()
                .setPayload(AuthConstants.CLAIM_USER, userId)
                .setPayload(AuthConstants.CLAIM_JTI, jti)
                .setPayload(AuthConstants.CLAIM_TYPE, type)
                // iat 必写：它是"口令变更后旧令牌立即失效"的唯一判据，漏写会让改密强制下线失效
                .setIssuedAt(new Date())
                .setExpiresAt(new Date(System.currentTimeMillis() + ttl.toMillis()))
                .setSigner(jwtSigner)
                .sign();
    }

    /**
     * 解析并校验令牌：验签 → 校验 exp 存在 → 校验未过期 → 校验 jti/type/user 存在 → 组装载荷。
     *
     * 【注意】必须强制校验 exp：hutool 的 {@code validateDate()} 对没有 exp 的令牌既不报错也不判过期，
     * 因此缺少 exp 的令牌会被永久接受；再叠加 TokenPayload.remainSeconds() 给不出 TTL（返回 -1）、
     * logout 也拉黑不了它，结果就是一枚"永不过期且不可撤销"的令牌。
     * 故这里把 exp 提升为必填，缺失即按非法令牌拒绝。
     *
     * @return 令牌载荷，字段口径见 TokenPayload；userId 无法解析为数字时按非法令牌处理
     * @throws TokenExpiredException 令牌已过期（调用方可尝试刷新）
     * @throws UnauthorizedException 令牌非法（签名、格式，或缺少 exp/jti/type/user 任一必需 claim）
     */
    public TokenPayload parse(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new UnauthorizedException("未登录");
        }
        JWT jwt;
        try {
            jwt = JWT.of(token).setSigner(jwtSigner);
        } catch (Exception e) {
            throw new UnauthorizedException("无效的令牌", e);
        }
        if (!jwt.verify()) {
            throw new UnauthorizedException("无效的令牌");
        }
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (ValidateException e) {
            throw new TokenExpiredException("令牌已过期");
        }
        // exp 必填：validateDate 对无 exp 的令牌不报错，必须自己拦（见方法注释）
        Date expiresAt = readExpiresAt(jwt);
        if (expiresAt == null) {
            throw new UnauthorizedException("无效的令牌：缺少有效期");
        }
        // 双保险：即使 hutool 的日期校验口径变化，也按 exp 与当前时间显式比对一次
        if (expiresAt.getTime() <= System.currentTimeMillis()) {
            throw new TokenExpiredException("令牌已过期");
        }

        Object userPayload = jwt.getPayload(AuthConstants.CLAIM_USER);
        if (userPayload == null) {
            throw new UnauthorizedException("无效的令牌");
        }
        // jti 是黑名单与 refresh 轮换的唯一抓手；type 决定 access/refresh 的用途隔离。
        // 两者缺失都无法做主动失效，按非法令牌拒绝而不是留 null 让调用方各自兜底
        Object jti = jwt.getPayload(AuthConstants.CLAIM_JTI);
        if (jti == null || jti.toString().trim().isEmpty()) {
            throw new UnauthorizedException("无效的令牌：缺少 jti");
        }
        Object type = jwt.getPayload(AuthConstants.CLAIM_TYPE);
        if (type == null || type.toString().trim().isEmpty()) {
            throw new UnauthorizedException("无效的令牌：缺少 type");
        }

        TokenPayload payload = new TokenPayload();
        try {
            payload.setUserId(Long.valueOf(userPayload.toString()));
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("无效的令牌");
        }
        payload.setJti(jti.toString());
        payload.setType(type.toString());
        payload.setExpiresAt(expiresAt);
        payload.setIssuedAt(readIssuedAt(jwt));
        return payload;
    }

    /** 读取 exp（Hutool 在不同版本下可能返回 Long 秒值或 Date，这里统一处理） */
    private Date readExpiresAt(JWT jwt) {
        return readDateClaim(jwt, "exp");
    }

    /**
     * 读取 iat（签发时间）。与 exp 同款兼容处理。
     *
     * 【注意】返回 null 是合法的：存量令牌可能没有 iat（由尚未写入 iat 的签发端签发），
     * 调用方（网关的水位校验）必须按"跳过校验"处理，不能当成非法令牌 ——
     * 否则升级瞬间会让全站在用令牌集体失效。
     */
    private Date readIssuedAt(JWT jwt) {
        return readDateClaim(jwt, AuthConstants.CLAIM_IAT);
    }

    /** 读取时间类 claim：Hutool 可能返回 Number（秒）或 Date，统一转 Date；取不到返回 null */
    private Date readDateClaim(JWT jwt, String name) {
        Object value = jwt.getPayload(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof Number) {
            return new Date(((Number) value).longValue() * 1000L);
        }
        return null;
    }

    /**
     * 解析已过期的令牌以取出 userId，供注销 / 踢人使用。
     *
     * 【严禁】把本方法用于任何鉴权路径 —— 它完全不校验有效期，用在鉴权路径上等于取消令牌过期校验：
     * 任何已过期的令牌都会被当成有效身份，令牌寿命这道兜底约束就此失效。
     *
     * 【为什么必须有这个旁路】用户点注销的正常时机恰恰是 access 已经过期，
     * 若因过期就拿不到 userId，{@code TokenService#logout} 中的"作废该用户全部 refresh token"
     * 就永远不会执行，表现为"已登出但 refresh 仍能不断换取新 access"，即会话实际还活着。
     *
     * 【审计】本方法是全项目唯一不校验有效期的令牌解析入口，每次调用都会打一条 warn 日志，
     * 带上 userId/jti/type/exp 与调用方位置，用于发现"被当成普通解析用"的误用。
     *
     * @return 令牌载荷；token 为空、签名不合法、缺少用户标识、或令牌尚未过期（含无 exp）时返回 null，
     * 调用方按"无法识别身份"处理，不做任何动作
     */
    public TokenPayload parseAllowExpired(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        JWT jwt = parseExpiredJwt(token);
        if (jwt == null) {
            return null;
        }
        Object userPayload = jwt.getPayload(AuthConstants.CLAIM_USER);
        if (userPayload == null) {
            return null;
        }
        TokenPayload payload = new TokenPayload();
        try {
            payload.setUserId(Long.valueOf(userPayload.toString()));
        } catch (NumberFormatException e) {
            return null;
        }
        Object jti = jwt.getPayload(AuthConstants.CLAIM_JTI);
        Object type = jwt.getPayload(AuthConstants.CLAIM_TYPE);
        payload.setJti(jti == null ? null : jti.toString());
        payload.setType(type == null ? null : type.toString());
        payload.setExpiresAt(readExpiresAt(jwt));
        payload.setIssuedAt(readIssuedAt(jwt));

        // 旁路必须留痕：正常只应由 logout / 踢人 触发（低频）；
        // 一旦被别的路径调用，日志里能直接给出调用方类名与方法行号，从而发现误用。
        log.warn("【鉴权旁路审计】以不校验有效期的方式解析了令牌（应仅用于注销/踢人）: "
                        + "userId={}, jti={}, type={}, exp={}, 调用来源={}",
                payload.getUserId(), payload.getJti(), payload.getType(), payload.getExpiresAt(),
                callerOf());
        return payload;
    }

    /**
     * 取本次调用的来源（上层业务类名 + 行号），用于旁路审计。
     *
     * 【注意】只做"来源提示"而非访问控制：不抛异常、不阻断调用，避免把审计能力变成新的故障点。
     * 有意从栈顶往下跳过本类与 JDK 反射帧，取第一个业务帧。
     */
    private String callerOf() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.equals(JwtTool.class.getName()) || className.startsWith("java.lang.Thread")) {
                continue;
            }
            int dot = className.lastIndexOf('.');
            String simpleName = dot < 0 ? className : className.substring(dot + 1);
            return simpleName + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
        }
        return "unknown";
    }

    /**
     * 解析并验签一个已过期的令牌。
     *
     * 【实现说明】Hutool 的 {@link JWTValidator#validateDate()} 在时间不合法时抛异常，
     * 这里不调用它，只做三件事：解析、验签、按 exp 自行比对。
     *
     * @return 时间确实已过期的令牌；签名非法、格式错误或尚未过期（含无 exp）时返回 null
     */
    private JWT parseExpiredJwt(String token) {
        JWT jwt;
        try {
            jwt = JWT.of(token).setSigner(jwtSigner);
            if (!jwt.verify()) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        Date expiresAt = readExpiresAt(jwt);
        // 无 exp 的令牌一律不接受：它无法被拉黑（见 parse 的说明），注销时更不能采信
        if (expiresAt == null || expiresAt.getTime() > System.currentTimeMillis()) {
            return null;
        }
        return jwt;
    }
}
