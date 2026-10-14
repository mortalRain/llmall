package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户持有的券（券实例）的跨服务视图。
 *
 * llmall-api 不能反向依赖 llmall-promotion-service，交易域下单只需判断券的适用性，
 * 因此只放判定所需字段，避免把营销域的展示模型泄进交易域。
 */
@Data
@Schema(description = "我的券（券实例）跨服务视图")
public class UserVoucherDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 券实例 id（下单时传的就是它） */
    @Schema(description = "券实例id（user_voucher.id）")
    private Long id;

    /** 券模板 id */
    @Schema(description = "券模板id")
    private Long voucherId;

    /** 适用门店 id；为 null 表示全平台通用券 */
    @Schema(description = "适用门店id；为空表示全平台通用")
    private Long shopId;

    /** 1 未使用 2 已使用 3 已过期（已过期是查询时按 expire_time 判定的结果） */
    @Schema(description = "状态：1 未使用 2 已使用 3 已过期")
    private Integer status;

    /** 过期时间 */
    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    /** 抵扣金额，单位分 */
    @Schema(description = "抵扣金额（分）")
    private Long actualValue;

    /** 已绑定/已使用的订单 id */
    @Schema(description = "关联订单id")
    private Long orderId;
}
