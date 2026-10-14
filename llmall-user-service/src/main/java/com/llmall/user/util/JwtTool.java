package com.llmall.user.util;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.TokenExpiredException;
import com.llmall.common.exception.UnauthorizedException;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 签发与解析（用户服务侧）。
 *
 * 【注意】令牌带 jti 与 type（access/refresh）：jti 供登出黑名单与 refresh 轮换使用，
 * type 用于阻止 refresh token 直接访问业务接口。解析区分"已过期"与"非法"，
 * 过期抛 TokenExpiredException，供网关触发无感刷新。
 */
@Component
public class JwtTool {

    private final JWTSigner jwtSigner;

    public JwtTool(KeyPair keyPair) {
        this.jwtSigner = JWTSignerUtil.createSigner("rs256", keyPair);
    }

    /**
     * 签发令牌
     *
     * @param userId 用户 id
     * @param jti    令牌唯一编号
     * @param type   access / refresh
     * @param ttl    有效期
     */
    public String createToken(Long userId, String jti, String type, Duration ttl) {
        return JWT.create()
                .setPayload(AuthConstants.CLAIM_USER, userId)
                .setPayload(AuthConstants.CLAIM_JTI, jti)
                .setPayload(AuthConstants.CLAIM_TYPE, type)
                .setExpiresAt(new Date(System.currentTimeMillis() + ttl.toMillis()))
                .setSigner(jwtSigner)
                .sign();
    }

    /**
     * 解析并校验令牌
     *
     * @return 令牌载荷
     * @throws TokenExpiredException 令牌已过期（调用方可尝试刷新）
     * @throws UnauthorizedException 令牌非法
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
        Date expiresAt;
        try {
            JWTValidator.of(jwt).validateDate();
            expiresAt = readExpiresAt(jwt);
        } catch (ValidateException e) {
            throw new TokenExpiredException("令牌已过期");
        }

        Object userPayload = jwt.getPayload(AuthConstants.CLAIM_USER);
        if (userPayload == null) {
            throw new UnauthorizedException("无效的令牌");
        }

        TokenPayload payload = new TokenPayload();
        try {
            payload.setUserId(Long.valueOf(userPayload.toString()));
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("无效的令牌");
        }
        Object jti = jwt.getPayload(AuthConstants.CLAIM_JTI);
        Object type = jwt.getPayload(AuthConstants.CLAIM_TYPE);
        payload.setJti(jti == null ? null : jti.toString());
        payload.setType(type == null ? null : type.toString());
        payload.setExpiresAt(expiresAt);
        return payload;
    }

    /** 读取 exp（Hutool 在不同版本下可能返回 Long 秒值或 Date，这里统一处理） */
    private Date readExpiresAt(JWT jwt) {
        Object exp = jwt.getPayload("exp");
        if (exp == null) {
            return null;
        }
        if (exp instanceof Date) {
            return (Date) exp;
        }
        if (exp instanceof Number) {
            return new Date(((Number) exp).longValue() * 1000L);
        }
        return null;
    }
}
