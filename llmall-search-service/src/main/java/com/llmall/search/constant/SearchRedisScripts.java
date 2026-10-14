package com.llmall.search.constant;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 搜索域的 Redis Lua 脚本。
 *
 * 【注意】热词写入口匿名可访问，计数、续期、截断三条命令必须原子完成：拆开写会出现
 * "计数成功但 TTL 或上界没跟上"的中间态，ZSet 可被灌成既不过期也不收敛的巨型 key。
 */
public final class SearchRedisScripts {

    private SearchRedisScripts() {
    }

    /**
     * 热词计数：{@code ZINCRBY + EXPIRE + 截断} 一次原子往返完成，要么全部生效、要么一条都没生效。
     *
     * KEYS[1]=热词 ZSet；ARGV[1]=词；ARGV[2]=key 的 TTL（毫秒）；ARGV[3]=成员数上界。
     * 返回截断后的成员数。
     */
    public static final DefaultRedisScript<Long> HOT_WORD_INCR_WITH_TTL_AND_TRIM = new DefaultRedisScript<>(
            "redis.call('zincrby', KEYS[1], 1, ARGV[1]) "
                    + "redis.call('pexpire', KEYS[1], ARGV[2]) "
                    + "local max = tonumber(ARGV[3]) "
                    + "local size = redis.call('zcard', KEYS[1]) "
                    + "if size > max then "
                    // zremrangebyrank 的 rank 从 0 起（0=分数最低），删 [0, size-max-1] 即保留分数最高的 max 个
                    + "  redis.call('zremrangebyrank', KEYS[1], 0, size - max - 1) "
                    + "  size = max "
                    + "end "
                    + "return size",
            Long.class);
}
