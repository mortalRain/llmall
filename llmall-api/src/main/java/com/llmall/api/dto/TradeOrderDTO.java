package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 交易订单的跨服务视图（供 llmall-pay-service 在支付前校验订单状态）。
 *
 * 【注意】扣款前必须确认订单仍为"待付款"，否则会出现订单已超时关闭、用户仍支付成功的资损。
 * llmall-api 不能依赖 llmall-trade-service，所以这里只放校验所需字段。
 */
@Data
@Schema(description = "交易订单跨服务视图")
public class TradeOrderDTO {
    @Schema(description = "订单id")
    private Long id;
    @Schema(description = "用户id")
    private Long userId;
    @Schema(description = "订单状态：1 未付款 2 已付款 5 已取消 7 已核销")
    private Integer status;
    @Schema(description = "订单总金额（分）")
    private Integer totalFee;
    @Schema(description = "1 实物订单 2 门店套餐/团购券订单")
    private Integer orderType;

    /**
     * 门店 id。
     *
     * 券按门店发放与结算，营销域必须用订单自身的门店判断券能否用于本单，
     * 而不能相信调用方传来的 shopId。
     */
    @Schema(description = "门店id（券的适用门店需与之比对）")
    private Long shopId;

    /**
     * 本单锁定/使用的券实例 id（user_voucher.id），未使用券时为空。
     *
     * 营销域据此确认"这张券确实被这笔订单用掉"，而不是凭调用方传来的订单号核销。
     */
    @Schema(description = "订单使用的券实例id（user_voucher.id）")
    private Long voucherId;
}
