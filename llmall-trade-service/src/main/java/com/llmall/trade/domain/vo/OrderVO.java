package com.llmall.trade.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "订单页面VO")
public class OrderVO {
    @Schema(description = "订单id")
    private Long id;
    @Schema(description = "总金额，单位为分")
    private Integer totalFee;
    @Schema(description = "支付类型，1、支付宝，2、微信，3、扣减余额")
    private Integer paymentType;
    @Schema(description = "用户id")
    private Long userId;
    @Schema(description = "订单的状态，1、未付款 2、已付款,未发货 5、交易取消 7、已核销")
    private Integer status;
    @Schema(description = "1 实物订单 2 门店套餐/团购券订单")
    private Integer orderType;
    @Schema(description = "门店 id")
    private Long shopId;
    @Schema(description = "本单使用的券实例id（user_voucher.id，未用券为空）")
    private Long voucherId;
    @Schema(description = "核销码（到店出示）")
    private String verifyCode;
    @Schema(description = "核销时间")
    private LocalDateTime verifyTime;
    @Schema(description = "订单明细（列表页与详情页共用）")
    private List<OrderDetailVO> details;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "支付时间")
    private LocalDateTime payTime;
    @Schema(description = "发货时间")
    private LocalDateTime consignTime;
    @Schema(description = "交易完成时间")
    private LocalDateTime endTime;
    @Schema(description = "交易关闭时间")
    private LocalDateTime closeTime;
    @Schema(description = "评价时间")
    private LocalDateTime commentTime;
}
