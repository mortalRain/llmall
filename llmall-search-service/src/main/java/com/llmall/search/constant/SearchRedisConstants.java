package com.llmall.search.constant;

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
}
