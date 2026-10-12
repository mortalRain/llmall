package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * <p>
 * 支付订单
 * </p>
 */
@Data
@Schema(description = "支付单数据传输实体")
public class PayOrderDTO {
    @Schema(description = "id")
    private Long id;
    @Schema(description = "业务订单号")
    private Long bizOrderNo;
    @Schema(description = "支付单号")
    private Long payOrderNo;
    @Schema(description = "支付用户id")
    private Long bizUserId;
    @Schema(description = "支付渠道编码")
    private String payChannelCode;
    @Schema(description = "支付金额，单位分")
    private Integer amount;
    @Schema(description = "付类型，1：h5,2:小程序，3：公众号，4：扫码，5：余额支付")
    private Integer payType;
    @Schema(description = "付状态，0：待提交，1:待支付，2：支付超时或取消，3：支付成功")
    private Integer status;
    @Schema(description = "拓展字段，用于传递不同渠道单独处理的字段")
    private String expandJson;
    @Schema(description = "第三方返回业务码")
    private String resultCode;
    @Schema(description = "第三方返回提示信息")
    private String resultMsg;
    @Schema(description = "支付成功时间")
    private LocalDateTime paySuccessTime;
    @Schema(description = "支付超时时间")
    private LocalDateTime payOverTime;
    @Schema(description = "支付二维码链接")
    private String qrCodeUrl;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}