package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 交易订单的跨服务视图（供 llmall-pay-service 在支付前校验订单状态）。
 *
 * 为什么需要它：支付是"钱"的操作，必须在扣款前确认交易订单仍处于"待付款"，
 * 否则会出现"订单已超时关闭、用户仍支付成功"的资损场景（本项目实测踩过）。
 * llmall-api 不能依赖 llmall-trade-service，所以这里放一个只含校验所需字段的轻量 DTO。
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
     * 为什么交易订单要对外暴露门店：券是**按门店**发放与结算的（券券只允许在它的门店核销），
     * 营销域判断"这张券能不能用在这笔订单上"时必须拿到订单门店，
     * 否则就只能相信调用方传来的 shopId —— 而"调用方说的"正是所有越权问题的起点。
     */
    @Schema(description = "门店id（券的适用门店需与之比对）")
    private Long shopId;

    /**
     * 本单锁定/使用的券实例 id（user_voucher.id），未使用券时为空。
     *
     * 营销域据此判断"这张券确实是这笔订单用掉的"，而不是"谁传了个订单号就能核销我的券"。
     */
    @Schema(description = "订单使用的券实例id（user_voucher.id）")
    private Long voucherId;
}
