package com.llmall.common.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 规范化的单元测试。
 *
 * 【为什么值得测】traceId 会进日志行；若照抄外部请求头，攻击者可以用换行伪造日志。
 * 这里把"只接受字母数字与 . _ -、最长 64"的边界固定下来。
 */
class TraceContextTest {

    @Test
    void acceptsSafeValues() {
        assertThat(TraceContext.normalize("abc_123-X.Y")).isEqualTo("abc_123-X.Y");
        assertThat(TraceContext.normalize("  trim-me  ")).isEqualTo("trim-me");
    }

    @Test
    void rejectsValuesThatCouldForgeLogLines() {
        assertThat(TraceContext.normalize("abc\n123")).isNull();
        assertThat(TraceContext.normalize("abc\r123")).isNull();
        assertThat(TraceContext.normalize("abc 123")).isNull();
        assertThat(TraceContext.normalize("中文")).isNull();
    }

    @Test
    void rejectsOverlongValue() {
        String tooLong = "a".repeat(65);
        assertThat(TraceContext.normalize(tooLong)).isNull();
        assertThat(TraceContext.normalize("a".repeat(64))).hasSize(64);
    }

    @Test
    void rejectsNull() {
        assertThat(TraceContext.normalize(null)).isNull();
    }

    @Test
    void generatedValueIsStableInShape() {
        assertThat(TraceContext.generate()).hasSize(16).matches("[0-9a-f]{16}");
    }
}
