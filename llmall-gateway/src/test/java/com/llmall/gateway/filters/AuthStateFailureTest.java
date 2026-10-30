package com.llmall.gateway.filters;

import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.TokenExpiredException;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.security.JwtProperties;
import com.llmall.common.security.JwtTool;
import com.llmall.gateway.config.AuthProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthStateFailureTest {
    private JwtTool jwt;
    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> values;
    private AuthGlobalFilter filter;
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    @BeforeEach void setup() {
        jwt = mock(JwtTool.class);
        redis = mock(ReactiveStringRedisTemplate.class);
        values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.empty());
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(Flux.just(1L));
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(1L));
        when(jwt.parse("access")).thenReturn(payload(AuthConstants.TOKEN_TYPE_ACCESS));
        when(jwt.parse("expired")).thenThrow(new TokenExpiredException("fixture"));
        when(jwt.parse("refresh")).thenReturn(payload(AuthConstants.TOKEN_TYPE_REFRESH));
        when(jwt.createToken(anyLong(), anyString(), anyString(), any(Duration.class))).thenReturn("renewed");
        AuthProperties properties = new AuthProperties();
        properties.setExcludePaths(List.of("/search"));
        JwtProperties tokens = new JwtProperties();
        tokens.setTokenTTL(Duration.ofMinutes(30));
        filter = new AuthGlobalFilter(jwt, properties, tokens, redis, new BizMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(filter, "internalToken", "fixture-internal-credential-32-characters");
        forwarded.set(null);
    }

    @Test void protectedWriteCannotPassWhenBlacklistRedisFails() {
        blacklistFailure();
        unavailable(request("/orders", "access", true));
    }
    @Test void protectedReadCannotExposePrivateDataWhenWatermarkRedisFails() {
        when(values.get(anyString())).thenReturn(Mono.error(new IllegalStateException("fixture")));
        unavailable(request("/users/me", "access", false));
    }
    @Test void malformedWatermarkCannotBeTreatedAsAValidSession() {
        when(values.get(anyString())).thenReturn(Mono.just("not-a-timestamp"));
        unavailable(request("/users/me", "access", false));
    }
    @Test void emptyOrUnexpectedBlacklistResultCannotAuthorizeIdentity() {
        for (Flux<Long> result : List.of(Flux.<Long>empty(), Flux.just(2L))) {
            when(redis.execute(any(RedisScript.class), anyList())).thenReturn(result);
            MockServerWebExchange exchange = request("/users/me", "access", false);
            filter.filter(exchange, capture()).block();
            assertNull(forwarded.get());
            assertEquals(401, exchange.getResponse().getRawStatusCode());
        }
    }
    @Test void synchronousRedisFailureIsAlsoMappedTo503() {
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new IllegalStateException("fixture"));
        unavailable(request("/users/me", "access", false));
    }
    @Test void publicReadFallsBackToAnonymousWithoutForgedHeaders() {
        blacklistFailure();
        anonymous(request("/search", "access", false));
    }
    @Test void publicIdentityAlsoHonorsPasswordChangeWatermark() {
        when(values.get(anyString())).thenReturn(Mono.just("2000"));
        anonymous(request("/search", "access", false));
    }
    @Test void publicIdentityBecomesAnonymousWhenWatermarkCheckFails() {
        when(values.get(anyString())).thenReturn(Mono.error(new IllegalStateException("fixture")));
        anonymous(request("/search", "access", false));
    }
    @Test void refreshRedisFailureCannotMintANewAccessToken() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("fixture")));
        unavailable(request("/users/me", "expired", false));
    }
    @Test void refreshMustCheckWatermarkBeforeMintingAccess() {
        when(values.get(anyString())).thenReturn(Mono.error(new IllegalStateException("fixture")));
        unavailable(request("/users/me", "expired", false));
    }
    @Test void refreshCoveredByWatermarkIsRejectedAs401() {
        when(values.get(anyString())).thenReturn(Mono.just("2000"));
        MockServerWebExchange exchange = request("/users/me", "expired", false);
        filter.filter(exchange, capture()).block();
        assertNull(forwarded.get());
        assertEquals(401, exchange.getResponse().getRawStatusCode());
        verify(jwt, never()).createToken(anyLong(), anyString(), anyString(), any(Duration.class));
    }
    @Test void healthyAccessAndRefreshStillPass() {
        MockServerWebExchange access = request("/users/me", "access", false);
        filter.filter(access, capture()).block();
        assertEquals("42", forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.HEADER_USER_INFO));
        MockServerWebExchange refresh = request("/users/me", "expired", false);
        filter.filter(refresh, capture()).block();
        assertEquals("42", forwarded.get().getRequest().getHeaders().getFirst(AuthConstants.HEADER_USER_INFO));
        assertEquals("renewed", refresh.getResponse().getHeaders().getFirst(AuthConstants.HEADER_NEW_ACCESS_TOKEN));
    }
    @Test void downstreamFailureIsNotReclassifiedOrRetried() {
        RuntimeException failure = new RuntimeException("downstream fixture");
        AtomicInteger calls = new AtomicInteger();
        MockServerWebExchange exchange = request("/search", "access", false);
        RuntimeException actual = assertThrows(RuntimeException.class, () -> filter.filter(exchange, ex -> {
            calls.incrementAndGet();
            return Mono.error(failure);
        }).block());
        assertSame(failure, actual);
        assertEquals(1, calls.get());
    }

    private TokenPayload payload(String type) {
        TokenPayload payload = new TokenPayload();
        payload.setUserId(42L);
        payload.setJti(type + "-fixture");
        payload.setType(type);
        payload.setIssuedAt(new Date(1000));
        return payload;
    }
    private void blacklistFailure() {
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(Flux.error(new IllegalStateException("fixture")));
    }
    private MockServerWebExchange request(String path, String token, boolean post) {
        MockServerHttpRequest.BaseBuilder<?> builder = post ? MockServerHttpRequest.post(path) : MockServerHttpRequest.get(path);
        return MockServerWebExchange.from(builder.header(AuthConstants.HEADER_AUTHORIZATION, token)
                .header(AuthConstants.HEADER_REFRESH_TOKEN, "refresh")
                .header(AuthConstants.HEADER_USER_INFO, "999")
                .header(AuthConstants.HEADER_INTERNAL_TOKEN, "forged")
                .header(AuthConstants.HEADER_SERVICE_TOKEN, "forged").build());
    }
    private GatewayFilterChain capture() {
        return exchange -> { forwarded.set(exchange); return Mono.empty(); };
    }
    private void unavailable(MockServerWebExchange exchange) {
        assertDoesNotThrow(() -> filter.filter(exchange, capture()).block());
        assertNull(forwarded.get());
        assertEquals(503, exchange.getResponse().getRawStatusCode());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("\"code\":503"));
        assertNull(exchange.getResponse().getHeaders().getFirst("x-auth-error"));
        assertNull(exchange.getResponse().getHeaders().getFirst(AuthConstants.HEADER_NEW_ACCESS_TOKEN));
        verify(jwt, never()).createToken(anyLong(), anyString(), anyString(), any(Duration.class));
    }
    private void anonymous(MockServerWebExchange exchange) {
        filter.filter(exchange, capture()).block();
        assertNotNull(forwarded.get());
        for (String header : List.of(AuthConstants.HEADER_USER_INFO, AuthConstants.HEADER_INTERNAL_TOKEN, AuthConstants.HEADER_SERVICE_TOKEN)) {
            assertNull(forwarded.get().getRequest().getHeaders().getFirst(header));
        }
    }
}
