package com.llmall.promotion.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 券的支付密码入参（仅付费券需要）。
 *
 * <p>为什么单独开一个 DTO 而不是继续用 {@code @RequestParam String pw}：
 * 密码放在 URL query 里会被**访问日志、浏览器历史、Referer、代理与网关日志**多处留痕，
 * 属于典型的敏感信息泄露路径。HTTP 语义上，凭据应当放在请求体里随 POST 提交，
 * 而不是拼在 URL 上；这个 DTO 就是把这条纪律固定下来的载体。
 *
 * <p>与登录密码的区别：支付密码只用于"付费券扣款"这类资金动作，
 * 泄漏即可被用来消耗账户余额，因此按凭据管理，不做任何日志输出。
 */
@Data
public class VoucherPwDTO {

    @Schema(description = "支付密码：仅当券是付费券（pay_value>0）时必填；免费券可不传")
    private String pw;
}
