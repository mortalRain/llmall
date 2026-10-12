package com.llmall.gateway.util;

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
 * 网关侧 JWT 工具。
 *
 * 网关职责：验签、判定"过期 / 非法"、并在 access 过期时用 refresh token 换发新令牌（无感刷新）。
 * 因此网关既需要解析能力，也需要签发能力（refresh 换新 access 由网关就地完成，
 * 不必再绕一次用户服务，少一跳网络、少一次失败点）。
 */
@Component
public class JwtTool {

    private final JWTSigner jwtSigner;

    public JwtTool(KeyPair keyPair) {
        this.jwtSigner = JWTSignerUtil.createSigner("rs256", keyPair);
    }

    /** 签发令牌（网关用于无感刷新时下发新的 access token） */
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
     * 解析令牌
     *
     * @throws TokenExpiredException 令牌已过期（调用方据此触发无感刷新）
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

    /** 读取 exp（不同 Hutool 版本可能返回 Long 秒值或 Date） */
    private Date readExpiresAt(JWT jwt) {
        Object exp = jwt.getPayload("exp");
        if (exp instanceof Date) {
            return (Date) exp;
        }
        if (exp instanceof Number) {
            return new Date(((Number) exp).longValue() * 1000L);
        }
        return null;
    }
}
