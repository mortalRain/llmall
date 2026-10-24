package com.llmall.user.support;

import java.time.Duration;

/**
 * 弱口令缓冲期的策略：起点判定与 key 的存活时长。
 *
 * 【注意】key 的 TTL 必须严格长于缓冲期。两者相等时，Redis 会在缓冲期到期的同一刻删除 key，
 * 下一次登录读到 null 又会当成首次检测重新计时，于是"到期硬拦截"永远不会触发。
 * 这里把 TTL 显式定义为缓冲期加一周余量，并由 ttlCoversGrace 提供可断言的判据。
 */
public final class WeakPasswordPolicy {

    /** 缓冲期：从首次检测到弱口令起算，期内可登录并提示改密 */
    public static final Duration GRACE = Duration.ofDays(7);

    /** key 的存活时长：必须大于缓冲期，多留一周用于吸收时钟偏差与长期未登录的账号 */
    public static final Duration TTL = GRACE.plusDays(7);

    private WeakPasswordPolicy() {
    }

    /**
     * 是否已超过缓冲期。
     *
     * @param sinceMillis 首次检测到弱口令的时刻（毫秒）
     * @param nowMillis   当前时刻（毫秒）
     * @return 超过缓冲期返回 true；时钟回拨（now 小于 since）按未过期处理，避免误拦
     */
    public static boolean isExpired(long sinceMillis, long nowMillis) {
        if (nowMillis <= sinceMillis) {
            return false;
        }
        return nowMillis - sinceMillis > GRACE.toMillis();
    }

    /** TTL 是否覆盖缓冲期，供启动期或单元测试断言 */
    public static boolean ttlCoversGrace() {
        return TTL.compareTo(GRACE) > 0;
    }
}
