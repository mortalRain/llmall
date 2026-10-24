package com.llmall.content.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 推拉结合 Feed 的一页结果与下一页游标。
 *
 * 【为什么单独一个视图】v2 的翻页结果是两路数据源（收件箱 + 大V 补拉）归并后的产物，
 * 单一的 lastId 无法表达"两路各自读到哪"，因此把两个游标一起回给调用方。
 *
 * 【三个游标的分工】nextLi 是收件箱侧已消费到的记录 id；nextCt 与 nextCi 是合并时间轴上的
 * 最后一条的发布时间与笔记 id。nextCt 与 nextCi 必须成对使用，只有发布时间会在同一秒内重行。
 *
 * 【pullApplied】本页是否真的发生了补拉，用于区分"没有大V可补"与"补了但这一页没轮到"。
 */
@Data
@Accessors(chain = true)
public class FeedPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本页内容，按发布时间倒序 */
    private List<FeedItemVO> items;

    /** 下一页游标：收件箱侧 */
    private Long nextLi;

    /** 下一页游标：合并时间轴上的发布时间 */
    private LocalDateTime nextCt;

    /** 下一页游标：合并时间轴上的笔记 id */
    private Long nextCi;

    /** 本页是否发生了补拉 */
    private Boolean pullApplied;

    /**
     * 本页是否因"关注作者数超过补拉上限"而只覆盖了部分作者。
     *
     * 【为什么要回给客户端】截断本身是条数上界的代价，但不能静默：
     * 客户端可据此提示"部分关注内容未展示"，而不是让用户以为 Feed 就这些。
     */
    private Boolean pullTruncated;

    /**
     * 下一页补拉作者游标：非空时下一页请作为 ai 参数回传。
     *
     * 【为什么要单独一路游标】收件箱按 feed_inbox.id、补拉按 blog 时间轴，两者互不相干；
     * 关注数超过单页上限时，必须再有一个"作者扫到哪"的进度位，否则更早关注的大 V 永远轮不到。
     */
    private Long nextAuthorId;
}
