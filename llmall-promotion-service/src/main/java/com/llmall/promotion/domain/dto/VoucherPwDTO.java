package com.llmall.promotion.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 购买凭据仅随请求体传递；密码不进入持久化记录、URL或toString。 */
@Data
public class VoucherPwDTO {

    @Schema(description = "支付密码：仅当券是付费券（pay_value>0）时必填；免费券可不传")
    @lombok.ToString.Exclude
    private String pw;

    @Schema(description = "普通付费券的客户端请求标识；同一次请求的重试必须复用")
    private String requestId;
}
