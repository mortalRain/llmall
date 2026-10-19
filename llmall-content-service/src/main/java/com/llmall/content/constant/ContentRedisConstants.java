package com.llmall.content.constant;

import java.time.Duration;

/**
 * 内容域 Redis key：点赞 ZSet、关注/粉丝集合、粉丝数缓存与集合装载上限。
 */
public interface ContentRedisConstants {

    /** 笔记点赞 ZSet：blog:liked:{blogId}，member = userId，score = 点赞时间戳（用于"点赞排行榜"） */
    String BLOG_LIKED_KEY = "blog:liked:";

    /** 我关注的人集合：follow:user:{userId} */
    String FOLLOW_KEY = "follow:user:";

    /** 关注我的人集合：follow:follower:{userId} */
    String FOLLOWER_KEY = "follow:follower:";

    /** 粉丝数缓存：follow:fans:count:{userId}，值为 DB 的 count(*)（只是缓存，权威在 follow 表） */
    String FANS_COUNT_KEY = "follow:fans:count:";

    /** 写扩散阈值：粉丝数超过该值的作者不做写扩散（跳过时打 WARN），改由粉丝读 Feed 时按作者补拉并合并 */
    int FEED_PUSH_THRESHOLD = 1000;

    /**
     * 关注/粉丝集合的 TTL（每次写入滑动续期）。
     *
     * 【注意】必须带 TTL：集合是可重建的缓存、权威在 follow 表，过期只多一次回源；但取关/注销
     * 留下的空壳 key 没人再读，Redis 默认 maxmemory-policy=noeviction，攒满内存后所有写命令直接报错。
     */
    Duration FOLLOW_SET_TTL = Duration.ofDays(7);

    /**
     * 粉丝数缓存的 TTL，刻意比集合短（10 分钟）：该数字要显示在主页/列表上，过期越短越不容易出现
     * "页面数字和实际差几个"；回源成本只有一条 count(*) 且 follow 表有 idx_followed 索引，代价可接受；
     * TTL 到期只是下次读回源，不会把计数清零。
     */
    Duration FANS_COUNT_TTL = Duration.ofMinutes(10);

    /**
     * 点赞 ZSet 的 TTL：排行榜只是"最近点赞的人"，权威数据在 blog_like 表；30 天足够长
     * （活跃笔记每次点赞都会续期），又保证没人看的笔记不会把 ZSet 永久留在内存里。
     */
    Duration BLOG_LIKED_TTL = Duration.ofDays(30);

    /**
     * 单个集合允许整体重建到 Redis 的上限：超过该量级的用户不再"整体装载"——
     * 几万成员的 SADD 会长时间阻塞 Redis 单线程，代价比一次索引扫描大得多，此时宁可放弃缓存、退化成 SQL 查询。
     */
    int CACHE_SET_MAX = 2000;
}
