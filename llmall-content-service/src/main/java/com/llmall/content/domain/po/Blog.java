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
 * 探店笔记 / 评价，其评分是门店热度计算的输入数据。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("blog")
public class Blog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private Long userId;

    private String title;

    /** 图片，多张以逗号分隔 */
    private String images;

    private String content;

    /** 评分，取值 1~5，用于计算门店热度 */
    private Integer score;

    /** 点赞数，同时写 Redis ZSet 排行榜 */
    private Integer liked;

    private Integer comments;

    /** 0 待审核 1 已发布 2 已屏蔽 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
