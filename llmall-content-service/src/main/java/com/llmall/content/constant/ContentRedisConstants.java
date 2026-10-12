package com.llmall.content.constant;

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

    /**
     * 写扩散阈值：粉丝数超过该值的作者不再推送（避免一次发布写几十万行）。
     * 这类作者的内容由粉丝读 Feed 时"拉取 + 合并"，即推拉结合。
     */
    int FEED_PUSH_THRESHOLD = 1000;
}
