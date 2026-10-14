package com.llmall.common.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路追踪 ID（traceId）的口径。
 *
 * 网关生成并全程透传（入口已有 X-Trace-Id 则沿用，但必须先过 {@link #normalize} 校验）；
 * 跨线程靠 SLF4J MDC 传递，异步线程需显式传递（同 UserContextTaskDecorator 传递用户）；
 * 响应头回写同一个 traceId，供对外报障时定位整条链路。
 *
 * 【注意】traceId 会进日志，不能照抄请求头：只接受"字母数字 + . _ -、长度不超过 64"的形态，
 * 否则外部可注入换行伪造日志行；不合格即视为未传，自行生成。
 */
public final class TraceContext {

    /** 透传用的请求/响应头（网关 → 服务 → Feign 下游 → MQ 消息属性）。 */
    public static final String HEADER = "X-Trace-Id";

    /** MDC 的 key，与 shared-log.yaml 里日志格式的 {@code %X{traceId}} 对应。 */
    public static final String MDC_KEY = "traceId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private TraceContext() {
    }

    /** 当前线程的 traceId，没有则返回 null（MQ 消费线程、定时任务线程需要自行 set）。 */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 校验外部传入的 traceId：合格则返回原值，不合格（含 null/空/超长/带换行）返回 null。 */
    public static String normalize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        return SAFE.matcher(trimmed).matches() ? trimmed : null;
    }

    /** 生成一个新的 traceId（32 位十六进制去掉分隔符后取前 16 位）。 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 当前线程没有 traceId 时生成一个并写入 MDC，返回最终生效的值。 */
    public static String currentOrGenerate() {
        String current = current();
        if (current != null) {
            return current;
        }
        String generated = generate();
        set(generated);
        return generated;
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    /** 必须在请求/消息处理结束时调用：线程是复用的，不清理会把 traceId 带到下一个请求上（串号）。 */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
