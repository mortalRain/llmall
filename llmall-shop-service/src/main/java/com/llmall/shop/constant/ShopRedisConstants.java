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

    /** 正常数据缓存时长（分钟） */
    long CACHE_TTL_MINUTES = 30;

    /** 空值缓存时长（分钟）：防止缓存穿透把请求全部打到数据库 */
    long NULL_CACHE_TTL_MINUTES = 2;

    /** 重建锁时长（秒）：防止持锁线程挂掉导致锁无法释放 */
    long LOCK_TTL_SECONDS = 10;

    /** 缓存 TTL 随机抖动上限（分钟）：防止大量 key 同时过期引发缓存雪崩 */
    long CACHE_TTL_JITTER_MINUTES = 5;
}
