package com.llmall.promotion.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 券的支付密码入参（仅付费券需要）。
 *
 * 单独开 DTO 而不是用 {@code @RequestParam String pw}：密码拼在 URL 上会被访问日志、浏览器历史、
 * Referer 以及代理与网关日志多处留痕，属于典型的敏感信息泄露路径；凭据应当随 POST 请求体提交。
 *
 * 【注意】支付密码只用于付费券扣款这类资金动作，泄漏即可被用来消耗账户余额，禁止任何日志输出。
 */
@Data
public class VoucherPwDTO {

    @Schema(description = "支付密码：仅当券是付费券（pay_value>0）时必填；免费券可不传")
    private String pw;
}
