package com.llmall.promotion.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 优惠券（券模板），对应 `llmall-promotion`.`voucher`。
 *
 * 刻意取舍：id 用 {@link IdType#AUTO}，与显式插入的演示数据（8001~8010）保持同一量级，用雪花 ID 会是 19 位；
 * rules 在库里是 JSON 字符串、由业务层解析，拆成列则每加一条规则都要 DDL（代价是不能用 SQL 按规则检索）；
 * type/status 用 int 常量而非枚举：属随时可能扩展的弱枚举，且 MP 枚举处理器要求实现 IEnum 并落库。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("voucher")
public class Voucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 上架：可领取 */
    public static final int STATUS_ON = 1;
    /** 下架：不可领取 */
    public static final int STATUS_OFF = 2;

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_DISCOUNT = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属门店 id（NULL 表示平台通用券） */
    private Long shopId;

    private String title;

    private String subTitle;

    /** 使用规则：JSON 字符串（有效天数/限购/使用门槛） */
    private String rules;

    /** 支付金额，单位分 */
    private Long payValue;

    /** 抵扣金额，单位分 */
    private Long actualValue;

    /** 0 普通券 1 折扣券/秒杀券 */
    private Integer type;

    /** 1 上架 2 下架 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
