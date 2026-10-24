package com.llmall.gateway.filters;

import com.llmall.common.constant.AuthConstants;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.security.JwtProperties;
import com.llmall.common.security.JwtTool;
import com.llmall.gateway.config.AuthProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 网关鉴权的行为测试：固定住"用哪条脚本、返回码怎么解释"这两个最容易改错的地方。
 *
 * 【为什么需要它】本类曾出现过"黑名单复用了 refresh 脚本"的事故，表现为所有带鉴权请求 401；
 * 注释能描述原因，但只有行为测试能保证下一次改动不会把语义改回去。
 */
class AuthGlobalFilterTest {

    private static final String INTERNAL_TOKEN = "internal-token-for-test";

    private JwtTool jwtTool;
    private ReactiveStringRedisTemplate redis;
    private AuthGlobalFilter filter;
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        jwtTool = new JwtTool(keyPair);

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setTokenTTL(Duration.ofMinutes(30));
        jwtProperties.setRefreshTTL(Duration.ofDays(7));

        AuthProperties authProperties = new AuthProperties();
        authProperties.setExcludePaths(Arrays.asList("/search"));
        authProperties.setUnsafeExcludePaths(Arrays.asList("/users/login"));

        redis = Mockito.mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> valueOperations = Mockito.mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(Mono.empty());

        filter = new AuthGlobalFilter(jwtTool, authProperties, jwtProperties, redis,
                new BizMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(filter, "internalToken", INTERNAL_TOKEN);
        forwarded.set(null);
    }

    @SuppressWarnings("unchecked")
    private void stubAuthState(long state) {
        when(redis.execute(Mockito.<RedisScript<Long>>any(), anyList(), anyList()))
                .thenReturn(Flux.just(state));
        when(redis.execute(Mockito.<RedisScript<Long>>any(), anyList()))
                .thenReturn(Flux.just(state));
    }

    private GatewayFilterChain captureChain() {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }

    private String accessToken() {
        return jwtTool.createToken(42L, "access-jti", AuthConstants.TOKEN_TYPE_ACCESS, Duration.ofMinutes(30));
    }

    @Test
    void validTokenIsForwardedWithTrustedHeaders() {
        stubAuthState(1L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/users/me").header(AuthConstants.HEADER_AUTHORIZATION, accessToken()).build());

        filter.filter(exchange, captureChain()).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.HEADER_USER_INFO)).isEqualTo("42");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.HEADER_INTERNAL_TOKEN))
                .isEqualTo(INTERNAL_TOKEN);
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.HEADER_SERVICE_TOKEN)).isNull();
    }

    @Test
    void blacklistedTokenGets401WithRevokedReason() {
        stubAuthState(0L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/users/me").header(AuthConstants.HEADER_AUTHORIZATION, accessToken()).build());

        filter.filter(exchange, captureChain()).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getHeaders().getFirst("x-auth-error")).isEqualTo("revoked");
    }

    @Test
    void missingTokenGets401NotLogin() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/users/me").build());

        filter.filter(exchange, captureChain()).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getHeaders().getFirst("x-auth-error")).isEqualTo("not-login");
    }

    @Test
    void whitelistedReadIsAnonymousWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/search").build());

        filter.filter(exchange, captureChain()).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.HEADER_USER_INFO)).isNull();
    }

    @Test
    void whitelistedReadStillStripsForgedIdentityHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/search")
                .header(AuthConstants.HEADER_USER_INFO, "999")
                .header(AuthConstants.HEADER_INTERNAL_TOKEN, "forged")
                .build());

        filter.filter(exchange, captureChain()).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.HEADER_USER_INFO)).isNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.HEADER_INTERNAL_TOKEN)).isNull();
    }

    @Test
    void unsafeWhitelistAllowsLoginPost() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/users/login").build());

        filter.filter(exchange, captureChain()).block();

        assertThat(forwarded.get()).isNotNull();
    }

    @Test
    void refreshTokenCannotBeUsedAsAccess() {
        String refresh = jwtTool.createToken(42L, "refresh-jti",
                AuthConstants.TOKEN_TYPE_REFRESH, Duration.ofDays(7));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/users/me").header(AuthConstants.HEADER_AUTHORIZATION, refresh).build());

        filter.filter(exchange, captureChain()).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getHeaders().getFirst("x-auth-error")).isEqualTo("wrong-token-type");
    }
}
