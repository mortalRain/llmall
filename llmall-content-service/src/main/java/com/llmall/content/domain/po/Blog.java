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
 * 探店笔记 / 评价（来源：点评 tb_blog）。
 *
 * 合并改造点：新增 score（评分 1~5）—— 它是"评价 → 门店热度"这条闭环的输入；
 * 点评原表只有正文没有评分，因此无法参与热度计算。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("blog")
public class Blog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联门店 id */
    private Long shopId;

    /** 作者 id */
    private Long userId;

    private String title;

    /** 图片，多张以逗号分隔 */
    private String images;

    /** 正文 */
    private String content;

    /** 评分 1~5（用于计算门店热度） */
    private Integer score;

    /** 点赞数（同时写 Redis ZSet 排行榜） */
    private Integer liked;

    /** 评论数 */
    private Integer comments;

    /** 0 待审核 1 已发布 2 已屏蔽 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
