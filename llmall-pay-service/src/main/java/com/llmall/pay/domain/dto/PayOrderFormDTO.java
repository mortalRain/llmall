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
     * 支付单 id。
     *
     * 【注意】这里**故意不加 @NotNull**：id 来自路径变量 `POST /pay-orders/{id}`，
     * 控制器是在方法体里 `payOrderFormDTO.setId(id)` 补进去的，而 @Valid 的校验发生在
     * 进入方法体之前 —— 一旦给 id 加 @NotNull，前端只传 `{"pw":"..."}` 的合法请求会被
     * 直接判成"支付订单id不能为空"（400），正常支付流程被打断。校验应该只针对请求体真正携带的字段。
     */
    @Schema(description = "支付订单id（由路径变量注入，无需请求体携带）")
    private Long id;
    @Schema(description = "支付密码")
    // @NotNull 挡不住空串：前端传 pw:"" 时会一路走到用户服务去做一次必然失败的密码校验，
    // 用户看到的是"密码错误"而不是"没填密码"；@NotBlank 让原因在入口就准确表达出来。
    @NotBlank(message = "支付密码不能为空")
    private String pw;
}