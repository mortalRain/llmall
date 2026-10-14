package com.llmall.cart.constant;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;

/**
 * 购物车域的 Redis key 与策略参数。
 *
 * 【注意】DB 是唯一权威，Redis 里的 Hash 只是缓存：读路径必须能在未命中时回源 DB 并回填，
 * 不允许"Redis 里没有就当成空车"；写路径一律先写 DB 再整份重建缓存，且重建放在事务提交之后，
 * 避免"事务回滚了缓存却改了"。缓存只用 Hash 一个 key + 统一 TTL，不为每个条目建 key。
 */
public final class CartRedisConstants {

    private CartRedisConstants() {
    }

    /** 购物车缓存：Hash，key = cache:cart:{userId}，field = itemId，value = 条目 JSON */
    public static final String CART_CACHE_KEY = "cache:cart:";

    /**
     * "已知是空车"的占位字段名。
     *
     * 【注意】Redis 的 Hash 没法存"空"：字段全删掉之后 key 会消失，下一次读又变成"未命中"并回源 DB，
     * 缓存等于没生效。故用一个不会与 itemId 冲突的 {@code __} 前缀占位字段把 key 撑住并带上 TTL，
     * 读路径看到只有这个字段就确定是"已知空车"、直接返回空列表；真有条目时整份重建会 DEL 掉它。
     */
    public static final String EMPTY_CART_MARKER = "__empty__";

    /**
     * 购物车缓存的 TTL（分钟），每次重建刷新。
     *
     * 【注意】{@code cache:cart:{userId}} 是按用户新建的 key，没有 TTL 的话每个打开过购物车的用户
     * 都会永久留下一个 key。取 30 分钟：购物车是用户自己会反复看的高频数据，30 分钟内基本都会再被
     * 读到，而超过 30 分钟没人看的车回源一次 DB 的代价远小于把 key 永久留在内存里。
     */
    public static final Duration CART_CACHE_TTL = Duration.ofMinutes(30);
}
