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
 * 门店表（来源：点评 tb_shop）。
 *
 * 合并改造点：
 * 1. 新增 hotScore / hotUpdateTime —— 亮点 C 的"热度分"落点；
 * 2. 新增 lastReviewTime —— 热度时间衰减的锚点，避免每次重算都把衰减重置掉；
 * 3. 新增 status —— 营业状态（点评原表没有，而门店必然要能"打烊/下线"）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 门店名称 */
    private String name;

    /** 门店类型 id */
    private Long typeId;

    /** 门店图片，多张以逗号分隔 */
    private String images;

    /** 商圈 */
    private String area;

    /** 地址 */
    private String address;

    /** 经度 */
    private Double x;

    /** 纬度 */
    private Double y;

    /** 人均价格（分） */
    private Long avgPrice;

    /** 销量 */
    private Integer sold;

    /** 评价数 */
    private Integer comments;

    /** 评分 x10（45 表示 4.5 分） */
    private Integer score;

    /** 热度分（时间衰减后的综合评分） */
    private Double hotScore;

    /** 热度最近计算时间 */
    private LocalDateTime hotUpdateTime;

    /** 最近一次收到评价的时间（时间衰减锚点） */
    private LocalDateTime lastReviewTime;

    /** 营业时间 */
    private String openHours;

    /** 1 营业中 2 休息中 3 已下线 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
