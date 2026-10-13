package com.llmall.content.constant;

import java.time.Duration;

/**
 * 内容域 Redis key 与参数。
 */
public interface ContentRedisConstants {

    /** 笔记点赞 ZSet：blog:liked:{blogId}，member = userId，score = 点赞时间戳（用于"点赞排行榜"） */
    String BLOG_LIKED_KEY = "blog:liked:";

    /** 我关注的人集合：follow:user:{userId} */
    String FOLLOW_KEY = "follow:user:";

    /** 关注我的人集合：follow:follower:{userId} */
    String FOLLOWER_KEY = "follow:follower:";

    /** 粉丝数缓存：follow:fans:count:{userId}，值为 DB 的 count(*)（**只是缓存，权威在 follow 表**） */
    String FANS_COUNT_KEY = "follow:fans:count:";

    /**
     * 写扩散阈值：粉丝数超过该值的作者不再推送（避免一次发布写几十万行）。
     * 这类作者的内容由粉丝读 Feed 时"拉取 + 合并"，即推拉结合。
     */
    int FEED_PUSH_THRESHOLD = 1000;

    /**
     * 关注/粉丝集合的 TTL（每次写入滑动续期）。
     *
     * <p>口径：集合是<b>可重建的缓存</b>，权威数据在 follow 表 —— 过期不等于丢数据，
     * 只是下次读多一次回源；但**必须**带 TTL：取关、注销账号留下的空壳 key 是"没人再读"的僵尸数据，
     * 而 Redis 默认 maxmemory-policy=noeviction，这类 key 攒满内存后所有写命令直接报错，
     * 故障面远比"缓存穿透"大。
     */
    Duration FOLLOW_SET_TTL = Duration.ofDays(7);

    /**
     * 粉丝数缓存的 TTL。
     *
     * <p>口径：刻意比集合短（10 分钟）。粉丝数是要显示在主页/列表上的数字，过期越短越不容易出现
     * "页面数字和实际差几个"的观感问题；回源成本只有一条 count(*)，且 follow 表有 idx_followed
     * (follow_user_id) 索引，代价可接受。TTL 到期只是"下次读回源"，不会像旧实现那样把计数清零。
     */
    Duration FANS_COUNT_TTL = Duration.ofMinutes(10);

    /**
     * 点赞 ZSet 的 TTL：排行榜是"最近点赞的人"，权威数据在 blog_like 表。
     * 30 天足够长（活跃笔记每次点赞都会续期），又保证没人看的笔记不会把 ZSet 永久留在内存里。
     */
    Duration BLOG_LIKED_TTL = Duration.ofDays(30);

    /**
     * 单个集合允许整体重建到 Redis 的上限。
     *
     * <p>超过这个量级的用户不再"整体装载"：几万成员的 SADD 会长时间阻塞 Redis 单线程，
     * 代价比一次索引扫描大得多 —— 此时宁可放弃缓存，退化成 SQL 查询。
     */
    int CACHE_SET_MAX = 2000;
}
