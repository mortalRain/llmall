package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 余额扣款请求体（llmall-pay-service → llmall-user-service 内部调用）。
 *
 * 【注意】支付密码必须走请求体，禁止放进 URL query：请求行会进入 Feign 日志、
 * 网关/Web 容器的 access log（不受应用日志级别控制）以及 APM 链路追踪。
 * bizNo（订单号）用于对账追溯，不参与鉴权，可为空。
 */
@Schema(description = "余额扣款请求体")
@Data
@Accessors(chain = true)
public class MoneyDeductDTO {

    @Schema(description = "支付密码（只在请求体里传，绝不放进 URL/query）")
    @NotBlank(message = "支付密码不能为空")
    private String pw;

    @Schema(description = "扣款金额，单位分，必须大于 0")
    @NotNull(message = "扣款金额不能为空")
    @Min(value = 1, message = "扣款金额必须大于 0")
    private Integer amount;

    @Schema(description = "关联业务号（订单号，可选，仅用于对账追溯）")
    private String bizNo;
}
