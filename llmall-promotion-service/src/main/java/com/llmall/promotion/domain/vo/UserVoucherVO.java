package com.llmall.promotion.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * "我的券"视图：把 user_voucher（券实例）与 voucher（券模板）里前端要用的字段合成一个对象，
 * 避免前端拿到 id 之后再逐个查标题/面额。
 */
@Data
@Accessors(chain = true)
public class UserVoucherVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 券实例 id（核销 /vouchers/my/{id}/use 用的就是它） */
    private Long id;

    private Long voucherId;

    /** 券标题（来自 voucher） */
    private String title;

    private String subTitle;

    private String rules;

    /** 抵扣金额，单位分（来自 voucher） */
    private Long actualValue;

    /** 门店 id（冗余自 user_voucher，便于按门店核销） */
    private Long shopId;

    /** 关联订单 id */
    private Long orderId;

    /** 1 未使用 2 已使用 3 已过期：已过期是**返回时按 expire_time 判定的结果**，不一定等于库里的值 */
    private Integer status;

    private LocalDateTime receiveTime;

    private LocalDateTime useTime;

    private LocalDateTime expireTime;
}
