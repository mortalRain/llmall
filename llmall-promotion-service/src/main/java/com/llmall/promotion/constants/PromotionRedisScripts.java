package com.llmall.promotion.constants;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 促销域的 Redis Lua 脚本：这里只放短小的"重建集合 + 设 TTL"型脚本，
 * 秒杀脚本本身较长、独立放在 {@code resources/lua/seckill.lua}。
 */
public final class PromotionRedisScripts {

    private PromotionRedisScripts() {
    }

    /**
     * 批量重建集合：{@code DEL + SADD(全部成员) + EXPIRE} 必须一次原子完成 ——
     * 分三条命令写会留下"部分写入"的已购集合（{@code seckill:ordered:{voucherId}}），
     * 使"一人一单"的第一道闸门形同虚设；只写成员不执行 EXPIRE 则会留下永不回收的无 TTL 集合。
     *
     * KEYS[1]=集合 key；ARGV[1]=TTL（秒）；ARGV[2..]=全部成员。
     * 返回写入的成员数（0 表示没有成员，此时不设 TTL，避免留下一个空壳 key）。
     */
    public static final DefaultRedisScript<Long> SET_REBUILD_WITH_TTL = new DefaultRedisScript<>(
            "redis.call('del', KEYS[1]) "
                    + "for i = 2, #ARGV do redis.call('sadd', KEYS[1], ARGV[i]) end "
                    + "local n = #ARGV - 1 "
                    + "if n > 0 then redis.call('expire', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);
}
