package com.llmall.cart.constant;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 购物车域的 Redis Lua 脚本。
 *
 * 【注意】重建缓存必须原子：DEL + HSET + EXPIRE 拆成三条命令时存在两个窗口 ——
 * 并发读会看到"空车"或只写了一半的"半份车"（静默的错误答案，比接口报错更难排查），
 * EXPIRE 失败还会留下没有 TTL 的 key。合并成一次 Lua 往返后要么全部生效、要么一条都没生效，
 * 读侧不可能观察到中间态。
 */
public final class CartRedisScripts {

    private CartRedisScripts() {
    }

    /**
     * 整份重建购物车 Hash：{@code DEL + HSET(全部字段) + EXPIRE} 一次完成，不可被打断。
     *
     * 【注意】字段数为 0 时不能什么都不写：key 会不存在，下次读又变成未命中而回源 DB
     * （空车用户量最大，缓存等于没生效），故写入占位字段 {@code __empty__} 撑住 key 并设上 TTL。
     * 整份重建而非只改变动字段：一旦 Java 侧判断错该删哪个 field，就会在缓存里留下 DB 里
     * 已经不存在的条目；条目数有硬上限，整份重建成本可以忽略。
     *
     * KEYS[1]=购物车 Hash key；ARGV[1]=TTL（秒）；其后为成对的 field, value。
     * 返回写入的真实条目数（不含占位字段，便于日志与断言）。
     */
    public static final DefaultRedisScript<Long> CART_REBUILD = new DefaultRedisScript<>(
            "redis.call('del', KEYS[1]) "
                    + "local n = 0 "
                    + "for i = 2, #ARGV, 2 do "
                    + "  redis.call('hset', KEYS[1], ARGV[i], ARGV[i + 1]) "
                    + "  n = n + 1 "
                    + "end "
                    + "if n == 0 then "
                    + "  redis.call('hset', KEYS[1], '" + CartRedisConstants.EMPTY_CART_MARKER + "', '1') "
                    + "end "
                    + "redis.call('expire', KEYS[1], ARGV[1]) "
                    + "return n",
            Long.class);
}
