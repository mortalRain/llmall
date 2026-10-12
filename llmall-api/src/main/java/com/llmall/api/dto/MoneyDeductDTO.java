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
 * <h3>为什么必须有这个 DTO，而不是继续用 query 参数</h3>
 * 原接口是 {@code PUT /users/money/deduct?pw=123456&amount=25600} —— 支付密码出现在 URL 里，
 * 后果是三重泄露：
 *  ① Feign 的日志级别当时是 FULL，请求行（含完整 URL）被原样打到 llmall-pay-service 的日志里，
 *     实测日志里就能 grep 到 {@code pw=123456}；
 *  ② URL 会进入网关/Nginx/Tomcat 的 access log（那套日志通常不受应用日志级别控制，也不归应用管）；
 *  ③ 浏览器、代理、APM 链路追踪记录 URL 是行业惯例，等于凭据被复制到一堆没人想过的地方。
 * 密码属于必须在**请求体**里传的凭据：body 不会进 access log，且可以随日志级别被整体关闭。
 *
 * <p>bizNo（订单号）用于把这次扣款与业务对齐，便于对账时定位是哪一笔支付的扣款，
 * 不参与鉴权，可为空。
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
