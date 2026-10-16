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

    /**
     * 热词 ZSet 的 TTL，由写入路径的 Lua 每次 pexpire 续期（滑动过期）。
     * 【注意】它只是"闲置到期即回收"，不是按窗口淘汰旧热词：只要有搜索流量在写，key 就不过期，
     * score 始终是历史累计值。压住旧热词靠的是 HOT_WORD_MAX_SIZE 的按分截断，不能指望本 TTL。
     */
    Duration HOT_WORD_TTL = Duration.ofDays(7);
}
