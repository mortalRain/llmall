package com.llmall.search.constant;

import java.time.Duration;

/**
 * 搜索域 Redis key 与参数。
 */
public interface SearchRedisConstants {

    /** 热词 ZSet：member = 词，score = 搜索次数（ZINCRBY 原子累加） */
    String HOT_WORD_KEY = "search:hot:word";

    /** 落库时取用的热词数量 */
    int HOT_WORD_SYNC_TOP = 100;

    /** 词长上限：超长词直接丢弃，避免脏词把 ZSet 撑爆 */
    int HOT_WORD_MAX_LENGTH = 32;

    /**
     * 热词 ZSet 的成员上限（超出即按分数砍掉尾部）。
     * <p>写入口是**匿名可访问**的搜索接口，不设上限时任何人都能用脚本刷入海量随机词，
     * 把 ZSet 撑成内存炸弹；1000 足够覆盖真实长尾，超出部分对"热词榜"没有任何价值。
     */
    int HOT_WORD_MAX_SIZE = 1000;

    /**
     * 热词 ZSet 的 TTL（滚动窗口）。
     * <p>不设 TTL 的话 score 是"历史累计"：很久以前的热搜会永远排在今天前面，
     * 榜单失去时效性；过期即从零累计，等价于一个天然的滚动统计窗口。
     */
    Duration HOT_WORD_TTL = Duration.ofDays(7);
}
