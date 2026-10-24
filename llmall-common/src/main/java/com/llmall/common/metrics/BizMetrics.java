package com.llmall.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 业务指标的统一出口。
 *
 * 【为什么需要它】关键失败路径如果只写日志，故障只能靠人翻日志发现；
 * 指标能让"降级正在发生""验证码在被爆破""消息补偿失败"变成可告警的信号。
 *
 * 【命名口径】统一以 llmall. 开头，按域分段，Prometheus 侧会把点转成下划线。
 * 【注意】指标只做观测，不参与任何业务判定；指标后端不可用时由 Micrometer 自行降级，
 * 不允许因为打点失败影响主流程。
 */
public class BizMetrics {

    private final MeterRegistry registry;

    public BizMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 计数加一，tags 按 name/value 成对传入 */
    public void increment(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    /** 鉴权降级：reason 取 blacklist / watermark */
    public void authDegrade(String reason) {
        increment("llmall.auth.degrade", "reason", reason);
    }

    /** 验证码校验失败：scene 为场景，reason 取 mismatch / locked / expired */
    public void codeFailure(String scene, String reason) {
        increment("llmall.auth.code.failure", "scene", scene, "reason", reason);
    }

    /** 登录失败计数 */
    public void loginFailure(String reason) {
        increment("llmall.auth.login.failure", "reason", reason);
    }

    /** 消息补偿或退款失败，需人工介入 */
    public void compensationFailure(String domain, String action) {
        increment("llmall.compensation.failure", "domain", domain, "action", action);
    }
}
