package com.llmall.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 到店核销请求体。
 *
 * 为什么核销要带 shopId 而不是"只凭核销码"：核销码是被顾客拿在手里的凭据，
 * 光有码只能证明"顾客有这么一张券"，证明不了"这个门店有资格核销它"。
 * 门店身份（shopId）与券的适用门店必须对齐，否则任意一家门店都能把别家的券核销掉，
 * 结算时就成了"谁都能来分这笔钱"。所以门店侧调用时把自己的门店 id 一并传上来，
 * 归属校验在交易域完成（不信任调用方"我已经校验过了"的说法）。
 */
@Data
@Schema(description = "到店核销请求")
public class OrderVerifyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 核销码（顾客到店出示，支付成功后生成） */
    @NotBlank(message = "核销码不能为空")
    @Schema(description = "核销码", required = true, example = "A7K2M9Q4")
    private String verifyCode;

    /** 发起核销的门店 id */
    @NotNull(message = "门店id不能为空")
    @Schema(description = "核销门店id（必须与订单门店一致）", required = true, example = "1")
    private Long shopId;
}
