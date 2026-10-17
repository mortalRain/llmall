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

    /**
     * 评论状态「正常」，也是本仓库唯一的写入值。
     *
     * 【为什么要有这个名字】写入点若直接写 {@code setStatus(0)}，库里注释说"0 正常"、代码里只有一个魔数 0，两者只能靠人脑对齐；提成常量后"写的是什么状态"在代码里可读。
     * 【注意】1 被举报 / 2 禁止查看 两个状态目前没有任何代码产生（举报与审核链路未实现，见已知偏差台账），
     * 因此这里不为它们声明常量 —— 声明了反而会让人以为那两条路径已经存在。
     */
    public static final int STATUS_NORMAL = 0;

    /** 新评论的初始点赞数（计数初值，不是状态字典） */
    public static final int INITIAL_LIKED = 0;

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

