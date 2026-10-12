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
 * Feed 流收件箱（推模式）。
 *
 * 推拉结合的设计：
 *  - 粉丝数不多的作者：发布时**写扩散**（把笔记 id 推进每个粉丝的收件箱），粉丝读 Feed 时直接查收件箱，读很快；
 *  - 大 V（粉丝数超过阈值）：不写扩散（否则一次发布要写几十万行），改为粉丝读时**拉取**+合并。
 * 本项目实现推模式 + 阈值判断，作为"推拉结合"的最小完整实现。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("feed_inbox")
public class FeedInbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 收件人（粉丝） */
    private Long userId;

    /** 笔记 id */
    private Long blogId;

    /** 作者 id */
    private Long authorId;

    private LocalDateTime createTime;
}
