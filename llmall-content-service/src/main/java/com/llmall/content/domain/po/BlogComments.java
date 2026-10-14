package com.llmall.content.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 笔记评论（blog_comments 表）。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("blog_comments")
public class BlogComments implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long blogId;

    /** 父评论 id，0 表示一级评论 */
    private Long parentId;

    /** 被回复的评论 id，0 表示无 */
    private Long answerId;

    private String content;

    private Integer liked;

    /** 0 正常 1 被举报 2 禁止查看 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
