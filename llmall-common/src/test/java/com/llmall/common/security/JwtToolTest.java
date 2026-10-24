package com.llmall.common.security;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.TokenExpiredException;
import com.llmall.common.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发与校验的单元测试。
 *
 * 【为什么值得测】令牌是全站身份的唯一凭据，且这里的校验规则（exp 必填、类型隔离、过期与非法分流）
 * 都属于"改错不报错、只在线上表现为 401 或越权"的类型；用真实 RSA 密钥把契约固定下来成本很低。
 */
class JwtToolTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void issuedTokenCanBeParsed() {
        JwtTool tool = new JwtTool(keyPair);
        String token = tool.createToken(42L, "jti-1", AuthConstants.TOKEN_TYPE_ACCESS, Duration.ofMinutes(5));

        TokenPayload payload = tool.parse(token);
        assertThat(payload.getUserId()).isEqualTo(42L);
        assertThat(payload.getJti()).isEqualTo("jti-1");
        assertThat(payload.getType()).isEqualTo(AuthConstants.TOKEN_TYPE_ACCESS);
        assertThat(payload.getIssuedAt()).isNotNull();
        assertThat(payload.getExpiresAt()).isAfter(new Date());
        assertThat(payload.remainSeconds()).isPositive();
    }

    @Test
    void expiredTokenIsReportedAsExpired() {
        JwtTool tool = new JwtTool(keyPair);
        String token = tool.createToken(1L, "jti-2", AuthConstants.TOKEN_TYPE_ACCESS, Duration.ofSeconds(-10));
        assertThatThrownBy(() -> tool.parse(token)).isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void tokenWithoutExpIsRejected() {
        JwtTool tool = new JwtTool(keyPair);
        JWTSigner signer = JWTSignerUtil.createSigner("rs256", keyPair);
        String token = JWT.create()
                .setPayload(AuthConstants.CLAIM_USER, 1L)
                .setPayload(AuthConstants.CLAIM_JTI, "jti-3")
                .setPayload(AuthConstants.CLAIM_TYPE, AuthConstants.TOKEN_TYPE_ACCESS)
                .setSigner(signer)
                .sign();
        assertThatThrownBy(() -> tool.parse(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void tokenWithoutJtiIsRejected() {
        JwtTool tool = new JwtTool(keyPair);
        JWTSigner signer = JWTSignerUtil.createSigner("rs256", keyPair);
        String token = JWT.create()
                .setPayload(AuthConstants.CLAIM_USER, 1L)
                .setPayload(AuthConstants.CLAIM_TYPE, AuthConstants.TOKEN_TYPE_ACCESS)
                .setIssuedAt(new Date())
                .setExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                .setSigner(signer)
                .sign();
        assertThatThrownBy(() -> tool.parse(token)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        JwtTool other = new JwtTool(generator.generateKeyPair());
        String foreign = other.createToken(1L, "jti-4", AuthConstants.TOKEN_TYPE_ACCESS, Duration.ofMinutes(5));

        JwtTool tool = new JwtTool(keyPair);
        assertThatThrownBy(() -> tool.parse(foreign)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void parseAllowExpiredReturnsNullForStillValidToken() {
        JwtTool tool = new JwtTool(keyPair);
        String token = tool.createToken(7L, "jti-5", AuthConstants.TOKEN_TYPE_ACCESS, Duration.ofMinutes(5));
        assertThat(tool.parseAllowExpired(token)).isNull();
    }

    @Test
    void parseAllowExpiredStillReturnsUserForExpiredToken() {
        JwtTool tool = new JwtTool(keyPair);
        String token = tool.createToken(7L, "jti-6", AuthConstants.TOKEN_TYPE_ACCESS, Duration.ofSeconds(-5));
        TokenPayload payload = tool.parseAllowExpired(token);
        assertThat(payload).isNotNull();
        assertThat(payload.getUserId()).isEqualTo(7L);
    }

    @Test
    void blankTokenIsRejected() {
        JwtTool tool = new JwtTool(keyPair);
        assertThatThrownBy(() -> tool.parse(" ")).isInstanceOf(UnauthorizedException.class);
    }
}
