package com.llmall.content.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Feed 流单条内容：收件箱记录 + 笔记详情合并后的对外视图。
 *
 * 【为什么不再只返回 blogId】只给 id 时，前端要为每条笔记各发一次详情请求，N 条内容就是 N 次往返；
 * 阅读路径的放大点从服务端搬到了网络侧，总量一点没减少。这里把合并结果一次给出，
 * 服务端用一条 IN 查询取回整页笔记，请求数固定为 1 次、SQL 条数固定为 2 条（收件箱 + 笔记），与页大小无关。
 *
 * 【游标口径】继续翻页时，lastId 传本页最后一条的 {@code feedId}（收件箱记录 id），不是 blogId：
 * 收件箱按 id 倒序，只有收件箱 id 才能表达"读到哪一行了"。
 *
 * 【作者信息】authorId 取自收件箱记录（写扩散时落下），昵称等展示字段不在内容域库里，
 * 需要跨服务取（属于新增接口的范围，未纳入本次实现）；本视图不返回它，避免出现半截或猜测的字段。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class FeedItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 收件箱记录 id：滚动分页的游标 */
    private Long feedId;

    /** 笔记 id */
    private Long blogId;

    /** 作者用户 id（收件箱记录上的作者，与笔记的 userId 一致） */
    private Long authorId;

    /** 笔记所属门店 id */
    private Long shopId;

    /** 笔记标题 */
    private String title;

    /** 图片，多张以逗号分隔 */
    private String images;

    /** 正文 */
    private String content;

    /** 评分，取值 1~5 */
    private Integer score;

    /** 点赞数 */
    private Integer liked;

    /** 评论数 */
    private Integer comments;

    /** 笔记状态：0 待审核 1 已发布 2 已屏蔽（仅作者本人可见自己的未过审内容，判据与笔记详情一致） */
    private Integer status;

    /** 笔记发布时间 */
    private LocalDateTime createTime;
}
