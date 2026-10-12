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
 * 几个刻意的取舍：
 * 1. id 用 {@link IdType#AUTO}：库里就是 AUTO_INCREMENT，且演示数据（8001~8010）是显式插入的。
 *    若图省事用项目全局的雪花 ID，新券 id 会是 19 位，与演示数据不在一个量级，前端列表排序/展示都别扭。
 * 2. rules 在库里是一个 JSON 字符串（形如 {"有效天数":30,"限购":2,"使用门槛":"满100可用"}），
 *    这里用 String 原样承接、由业务层解析：券规则属于"运营随时会加一条"的展示型配置，
 *    拆成列意味着每次加规则都要 DDL；代价是不能用 SQL 按规则检索（本模块确实不需要）。
 * 3. type/status 用 int 常量而不是枚举：MyBatis-Plus 的枚举处理器要求实现 IEnum 并落库，
 *    而这两个字段是"随时可能扩展的弱枚举"，用常量更省事，避免升级 MP 时踩枚举映射的坑。
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

    /** 普通券 */
    public static final int TYPE_NORMAL = 0;
    /** 折扣券/秒杀券 */
    public static final int TYPE_DISCOUNT = 1;

    /** 券 id */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属门店 id（NULL 表示平台通用券） */
    private Long shopId;

    /** 券标题 */
    private String title;

    /** 副标题 */
    private String subTitle;

    /** 使用规则，JSON 字符串（如 有效天数/限购/使用门槛） */
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
