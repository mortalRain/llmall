package com.llmall.content.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 推拉结合 Feed 的一页结果与下一页游标。
 *
 * 两路数据源（收件箱 + 大V补拉）按统一的笔记发布时间/id归并，返回共同游标与hasMore。
 *
 * v2两路统一使用nextCt/nextCi（笔记发布时间/id），两字段必须成对回传。
 * nextLi/nextAuthorId保留JSON兼容，新客户端不以它们推进时间轴。
 *
 * 【pullApplied】本页是否真的发生了补拉，用于区分"没有大V可补"与"补了但这一页没轮到"。
 */
@Data
@Accessors(chain = true)
public class FeedPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本页内容，按发布时间倒序 */
    private List<FeedItemVO> items;

    /** 以候选而非可见条数判断后续页；本页因并发删除变短时也可能为true。 */
    private Boolean hasMore;

    /** 兼容字段；v2后续页以nextCt/nextCi为准。 */
    private Long nextLi;

    /** 下一页游标：已消费候选的笔记发布时间。 */
    private LocalDateTime nextCt;

    /** 下一页游标：合并时间轴上的笔记 id */
    private Long nextCi;

    /** 本页是否发生了补拉 */
    private Boolean pullApplied;

    /** 兼容字段；新版不按作者窗口截断，固定为false。 */
    private Boolean pullTruncated;

    /**
     * 兼容旧响应字段；新版统一查询所有关注的大V，不再切换作者窗口，固定为null。
     */
    private Long nextAuthorId;
}
