package com.llmall.user.config;

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

    /** access token 有效期（短） */
    private Duration tokenTTL = Duration.ofMinutes(30);

    /** refresh token 有效期（长）。必须明显长于 tokenTTL，否则无感刷新没有意义 */
    private Duration refreshTTL = Duration.ofDays(7);
}
