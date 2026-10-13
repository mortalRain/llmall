package com.llmall.shop.constant;

/**
 * 门店域 Redis key 与策略参数统一收口。
 *
 * 为什么集中管理：缓存 key 一旦在多处硬编码，改名时必然漏掉一处，
 * 结果是"缓存永远不命中"或"脏数据读不出来"这类排查成本极高的问题。
 */
public interface ShopRedisConstants {

    /** 门店详情缓存：cache:shop:{id} */
    String SHOP_CACHE_KEY = "cache:shop:";

    /** 缓存重建互斥锁：lock:shop:{id} */
    String SHOP_LOCK_KEY = "lock:shop:";

    /** 门店 GEO 索引（附近门店）：geo:shop */
    String SHOP_GEO_KEY = "geo:shop";

    /** 分类热度榜 ZSet：hot:shop:{typeId} */
    String SHOP_HOT_RANK_KEY = "hot:shop:";

    /** 全站热度榜 ZSet */
    String SHOP_HOT_RANK_ALL = "hot:shop:all";

    /** 门店访问 UV（HyperLogLog）：uv:shop:{date}:{shopId} */
    String SHOP_UV_KEY = "uv:shop:";

    /**
     * 正常数据的**逻辑过期**时长（分钟）。
     *
     * 口径说明：本值只是"逻辑过期"（写在缓存 JSON 的 expireTime 里），
     * Redis 上的物理 TTL 见 {@link #CACHE_PHYSICAL_EXTRA_MINUTES} 的说明 —— 两者必须分开算，
     * 否则物理 TTL 一到就把 key 删了，"返回旧值 + 异步重建"这条路径会直接失效。
     * 可通过 llmall.shop.cache.ttl-minutes 覆盖。
     */
    long CACHE_TTL_MINUTES = 30;

    /** 空值哨兵（DB 里也查不到）的逻辑过期时长（分钟）：防缓存穿透，宁可短一点 */
    long NULL_CACHE_TTL_MINUTES = 2;

    /** 空值哨兵物理 TTL 的随机抖动上限（分钟）：防止大批"不存在的 id"同时过期、同时回源 */
    long NULL_CACHE_TTL_JITTER_MINUTES = 1;

    /** 重建锁时长（秒）：防止持锁线程挂掉导致锁无法释放。必须大于一次重建的耗时 */
    long LOCK_TTL_SECONDS = 10;

    /** 缓存 TTL 随机抖动上限（分钟）：防止大量 key 同时过期引发缓存雪崩 */
    long CACHE_TTL_JITTER_MINUTES = 5;

    /**
     * 物理 TTL 相对"逻辑过期时间"的额外时长（分钟）。
     *
     * 【为什么物理 TTL 必须比逻辑过期时间长】详情缓存用的是逻辑过期方案：
     * key 在逻辑上过期后，仍要能**读到旧值**并交给一个线程异步重建。
     * 如果物理 TTL 等于逻辑 TTL，Key 会在逻辑过期的同一时刻被 Redis 删掉，
     * 于是"逻辑过期"退化成"未命中"，旧值拿不到、异步重建也没了触发条件（等于白做一套逻辑过期）。
     * 代价：极端情况下最多多留 CACHE_PHYSICAL_EXTRA_MINUTES 分钟的旧值，
     * 但**物理 TTL 仍然是有限的**，不会像原来那样永久驻留。
     */
    long CACHE_PHYSICAL_EXTRA_MINUTES = 10;

    /** 未命中路径没抢到重建锁时，最长等待多久（毫秒）后兜底自己回源 */
    long LOCK_WAIT_MILLIS = 500;

    /** 未命中路径没抢到重建锁时的轮询间隔（毫秒） */
    long LOCK_WAIT_INTERVAL_MILLIS = 20;
}
