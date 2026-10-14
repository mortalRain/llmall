package com.llmall.pay.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Builder;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@Builder
@Schema(description = "支付确认表单实体")
public class PayOrderFormDTO {
    /**
     * 支付单 id：来自路径变量 POST /pay-orders/{id}，由控制器在方法体内补入。
     *
     * 【注意】故意不加 @NotNull：@Valid 在进入方法体之前执行，加了会把只传 pw 的合法请求判成 400。
     */
    @Schema(description = "支付订单id（由路径变量注入，无需请求体携带）")
    private Long id;
    @Schema(description = "支付密码")
    // 用 @NotBlank 而非 @NotNull：空串会一路走到用户服务做一次必然失败的密码校验，报错原因不准确
    @NotBlank(message = "支付密码不能为空")
    private String pw;
}