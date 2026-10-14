package com.llmall.content.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Feed 流收件箱：推模式下作者发布笔记时把笔记 id 写入每个粉丝的收件箱，粉丝读 Feed 时直接查收件箱。
 *
 * 【注意】粉丝数超过阈值的作者不做写扩散（否则一次发布要写几十万行），改由粉丝读时拉取后合并。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("feed_inbox")
public class FeedInbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 收件人（粉丝）id */
    private Long userId;

    private Long blogId;

    private Long authorId;

    private LocalDateTime createTime;
}
