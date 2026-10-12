package com.llmall.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "llmall.jwt")
public class JwtProperties {
    private Resource location;
    private String password;
    private String alias;

    /** access token 有效期：网关用于签发"无感刷新"后的新令牌 */
    private Duration tokenTTL = Duration.ofMinutes(30);

    /** refresh token 有效期（仅用于文档与一致性约束，校验以 Redis 白名单为准） */
    private Duration refreshTTL = Duration.ofDays(7);
}
