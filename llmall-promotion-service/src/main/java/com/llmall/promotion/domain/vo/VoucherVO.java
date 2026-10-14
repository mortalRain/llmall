package com.llmall.promotion.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 券列表/详情视图。
 *
 * 秒杀券与普通券共用：差异只体现在 beginTime/endTime/remainingStock 有没有值，不必为两种券写两套接口。
 */
@Data
@Accessors(chain = true)
public class VoucherVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long shopId;

    private String title;

    private String subTitle;

    /** 使用规则（原样透传 JSON 字符串，由前端决定怎么展示） */
    private String rules;

    /** 支付金额，单位分 */
    private Long payValue;

    /** 抵扣金额，单位分 */
    private Long actualValue;

    /** 0 普通券 1 折扣券/秒杀券 */
    private Integer type;

    /** 1 上架 2 下架 */
    private Integer status;

    /** 秒杀开始时间（非秒杀券为 null） */
    private LocalDateTime beginTime;

    /** 秒杀结束时间（非秒杀券为 null） */
    private LocalDateTime endTime;

    /** 剩余库存（非秒杀券为 null）：优先取 Redis 预扣后的实时值，键不存在时回落到 DB */
    private Integer remainingStock;

    /** 当前是否处于秒杀时段；非秒杀券为 null，用于前端把"未开始/已结束"的按钮置灰 */
    private Boolean inSeckillTime;

    /** 当前登录用户是否已领取；未登录恒为 false（不泄露他人持有情况） */
    private Boolean received;
}
