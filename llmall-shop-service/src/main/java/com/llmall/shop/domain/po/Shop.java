package com.llmall.shop.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 门店（含热度分、营业状态等本域扩展字段）。
 *
 * 【注意】status 与 hotScore / hotUpdateTime / lastReviewTime 为本域扩展字段。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private Long typeId;

    /** 门店图片，多张以逗号分隔 */
    private String images;

    private String area;

    private String address;

    /** 经度 */
    private Double x;

    /** 纬度 */
    private Double y;

    /** 人均价格（分） */
    private Long avgPrice;

    private Integer sold;

    /** 评价数 */
    private Integer comments;

    /** 评分 x10（45 表示 4.5 分） */
    private Integer score;

    /** 热度分（时间衰减后的综合评分） */
    private Double hotScore;

    /** 热度最近计算时间 */
    private LocalDateTime hotUpdateTime;

    /** 最近一次收到评价的时间（时间衰减锚点，重算时不推进，避免衰减被重置） */
    private LocalDateTime lastReviewTime;

    private String openHours;

    /** 1 营业中 2 休息中 3 已下线 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
