package com.llmall.shop.constant;

/**
 * 门店域 Redis key 与缓存策略参数。
 *
 * 【注意】逻辑过期时长与物理 TTL 是两套口径，必须分开计：物理 TTL 若等于逻辑过期时长，
 * key 会在逻辑过期同一时刻被 Redis 删除，"读旧值 + 异步重建"路径随之失效。
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

    /** 门店访问 PV（当日访问次数计数器）：pv:shop:{date}:{shopId} */
    String SHOP_PV_KEY = "pv:shop:";

    /** 门店详情逻辑过期时长（分钟），可由 llmall.shop.cache.ttl-minutes 覆盖 */
    long CACHE_TTL_MINUTES = 30;

    /** 空值哨兵逻辑过期时长（分钟），防穿透，取值短于正常缓存 */
    long NULL_CACHE_TTL_MINUTES = 2;

    /** 空值哨兵物理 TTL 的随机抖动上限（分钟），避免大批不存在的 id 同时回源 */
    long NULL_CACHE_TTL_JITTER_MINUTES = 1;

    /** 重建锁持有时长（秒），必须大于一次重建耗时，防止持锁线程中断后锁不释放 */
    long LOCK_TTL_SECONDS = 10;

    /** 缓存物理 TTL 的随机抖动上限（分钟），避免大量 key 同时过期引发缓存雪崩 */
    long CACHE_TTL_JITTER_MINUTES = 5;

    /** 物理 TTL 相对逻辑过期时长的额外时长（分钟），代价为极端情况下多留该时长的旧值 */
    long CACHE_PHYSICAL_EXTRA_MINUTES = 10;

    /** 未命中且未抢到重建锁时的最长等待时长（毫秒），超时后自行回源 */
    long LOCK_WAIT_MILLIS = 500;

    /** 未命中且未抢到重建锁时的轮询间隔（毫秒） */
    long LOCK_WAIT_INTERVAL_MILLIS = 20;

    /**
     * 榜单 ZSet 与 GEO 索引的 TTL（分钟），每次写入滑动续期。
     *
     * 【注意】TTL 只负责回收、不负责修正，数据正确性依赖读路径回源重建与写路径续期。
     */
    long RANK_TTL_MINUTES = 7L * 24 * 60;

    /** 同一门店两次榜单成员写入的最小间隔（分钟），进程内兜底节流，不作为正确性依赖 */
    long RANK_WRITE_MIN_INTERVAL_MINUTES = 1;

    /** 门店不存在时写空值哨兵前的合并窗口（毫秒），把并发未命中收敛为一次写入 */
    long NULL_SENTINEL_MERGE_MILLIS = 50;

    /** 访问计数器保留天数，uv/pv key 都按天新建，无 TTL 会按天累积 */
    long UV_TTL_DAYS = 2;
}
