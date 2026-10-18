package com.llmall.gateway.filters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActuatorExposureFilter} 的最小分支覆盖：健康探针放行、其余运维端点拦截。
 *
 * 【为什么用 MockServerWebExchange 而不是起真网关】本机没有可用的容器运行时，起不来完整网关；
 * 而本类的判定只依赖"路径 + 是否调用下游"，用 Mock 交易所即可覆盖全部分支。
 * 端到端（真发起 HTTP 打 8080）属于未完成的验证，已在交付报告的遗留风险里登记。
 *
 * 【覆盖的重点是两个方向】只测"metrics 被拦"会漏掉"health 被误拦"这种更严重的回归 ——
 * 后者会让容器 healthcheck 与编排就绪判断一起失败，表现为"服务全 UP 但整套起不来"，
 * 因此放行方向单独成例，且覆盖 liveness / readiness 两个子探针。
 *
 * 【注意·断言必须用 getRawStatusCode】被测类用 {@code setRawStatusCode(404)} 写状态码，
 * 而 {@code AbstractServerHttpResponse} 的 {@code setRawStatusCode} 会把 {@code status} 置空 ——
 * 此时断言 {@code getStatusCode()} 恒为 {@code null}，测试会"通过"但什么都没验证到（假阴性）。
 * 所以这里统一断言 {@code getRawStatusCode()}。
 */
class ActuatorExposureFilterTest {

    /** 单次过滤的等待上限：纯内存断言，正常应在毫秒级完成，超时即视为失败 */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(2);

    private final ActuatorExposureFilter filter = new ActuatorExposureFilter();

    /**
     * 跑一次过滤器，记录"下游是否被调用"与"响应被写成了什么原始状态码"。
     *
     * @param path 请求路径
     * @return 观测结果（状态码取 {@code getRawStatusCode()}，放行时为 null）
     */
    private Observation run(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        AtomicBoolean passed = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            passed.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block(WAIT_TIMEOUT);
        return new Observation(passed.get(), exchange.getResponse().getRawStatusCode());
    }

    /** 一次过滤的观测结果：下游是否放行、原始状态码（放行时为 null） */
    private static final class Observation {
        private final boolean passed;
        private final Integer rawStatus;

        private Observation(boolean passed, Integer rawStatus) {
            this.passed = passed;
            this.rawStatus = rawStatus;
        }
    }

    @Test
    @DisplayName("放行：/actuator/health 交给下游，且不写状态码")
    void allowsHealth() {
        Observation o = run("/actuator/health");

        assertThat(o.passed).as("health 必须放行，否则容器 healthcheck 与编排就绪判断会一起失败").isTrue();
        assertThat(o.rawStatus).as("放行分支不应写任何状态码").isNull();
    }

    @Test
    @DisplayName("放行：/actuator/health/liveness 与 /readiness 两个子探针")
    void allowsHealthProbes() {
        assertThat(run("/actuator/health/liveness").passed).isTrue();
        assertThat(run("/actuator/health/readiness").passed).isTrue();
    }

    @Test
    @DisplayName("拦截：/actuator/metrics 返回 404 且不调用下游")
    void blocksMetrics() {
        Observation o = run("/actuator/metrics");

        assertThat(o.passed).as("被拦截的请求不能再进入下游处理器").isFalse();
        assertThat(o.rawStatus).as("必须是 404（403 等于确认了这个端点存在）")
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("拦截：/actuator/prometheus、/actuator/info、/actuator 根路径与 /actuator/env")
    void blocksOtherActuatorEndpoints() {
        for (String path : new String[]{"/actuator/prometheus", "/actuator/info", "/actuator", "/actuator/env"}) {
            Observation o = run(path);
            assertThat(o.passed).as("%s 不应放行", path).isFalse();
            assertThat(o.rawStatus).as("%s 应返回 404", path).isEqualTo(HttpStatus.NOT_FOUND.value());
        }
    }

    @Test
    @DisplayName("放行：非 actuator 的业务路径不受影响")
    void allowsBusinessPaths() {
        assertThat(run("/items/page").passed).isTrue();
        assertThat(run("/users/me").passed).isTrue();
    }

    @Test
    @DisplayName("陷阱：/actuatorXxx 这类同名开头的业务路径不能被误拦")
    void doesNotBlockLookAlikePaths() {
        Observation o = run("/actuatorXxx/mock");

        assertThat(o.passed).as("按路径段判断，不能把 /actuatorXxx 当成运维端点").isTrue();
        assertThat(o.rawStatus).isNull();
    }
}
