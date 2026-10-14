package com.llmall.search.constant;

import java.time.Duration;

/**
 * 搜索域 Redis key 与热词 ZSet 治理参数。
 */
public interface SearchRedisConstants {

    /** 热词 ZSet：member = 词，score = 搜索次数（ZINCRBY 原子累加） */
    String HOT_WORD_KEY = "search:hot:word";

    /** 落库时取用的热词数量 */
    int HOT_WORD_SYNC_TOP = 100;

    /** 词长上限，超长词直接丢弃 */
    int HOT_WORD_MAX_LENGTH = 32;

    /**
     * 热词 ZSet 成员上限，超出即按分数砍掉尾部。
     * 【注意】写入口是匿名可访问的搜索接口，不设上限时可用脚本刷入海量随机词把 ZSet 撑成内存炸弹。
     */
    int HOT_WORD_MAX_SIZE = 1000;

    /** 热词 ZSet 的 TTL（滚动窗口）；不设 TTL 时 score 是历史累计，旧热词会永远压住新热词 */
    Duration HOT_WORD_TTL = Duration.ofDays(7);
}
