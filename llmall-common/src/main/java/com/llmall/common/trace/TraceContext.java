package com.llmall.common.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路追踪 ID（traceId）的口径。
 *
 * <p>【要解决什么】一个用户请求进了网关，会被拆成"网关 → trade → item → pay"好几跳，
 * 中间还有 Feign 线程池与 MQ 异步。没有统一 ID 时，排查一次下单失败要在 4 份日志里
 * 按时间戳人肉对齐（并发一高就对不上）。traceId 的作用就是让这 4 份日志能被一条命令捞出来。
 *
 * <p>【三条约定】
 * <ol>
 *   <li>**网关生成、全程透传**：入口没有 {@code X-Trace-Id} 就生成一个；有就沿用
 *       （便于把前端/压测工具/上游网关的 ID 串起来），但必须先过白名单校验 —— 见 {@link #normalize}。</li>
 *   <li>**跨线程靠 MDC**：写进 SLF4J 的 MDC，日志格式里用 {@code %X{traceId}} 输出；
 *       异步线程要显式传递（本项目的异步线程池已用 UserContextTaskDecorator 传递用户，traceId 同理）。</li>
 *   <li>**对外可查**：响应头回写同一个 traceId，用户报障时报这一个值就能定位整条链路。</li>
 * </ol>
 *
 * <p>【为什么要校验而不是照抄请求头】追踪 ID 会进日志。原样采信外部传入的值等于允许
 * 攻击者往日志里注入换行/伪日志行（日志伪造），所以只接受"字母数字 + . _ -，长度 ≤ 64"的形态，
 * 不合格就当没传、自己生成一个。
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

    /** 生成一个新的 traceId（32 位十六进制去掉分隔符的前 16 位：足够唯一，又不至于把日志行撑长）。 */
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
