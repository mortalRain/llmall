package com.llmall.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 修改登录密码表单（供"用户名 + 密码"登录使用）。
 *
 * 【与支付密码表单的分工】两者字段结构与校验链路相同，差别只在改哪一列：
 * 本表单只写 password（登录口令），支付密码表单只写 pay_password。
 * 刻意保持为两个 DTO 而不复用一个：两者的字段语义与错误文案不同
 * （"原登录密码错误"与"原支付密码错误"），合并会让调用方分不清自己在改哪一类口令。
 *
 * 【边界约束】三个字段均必填，新口令长度 6~32 位，由 @NotBlank/@Size 先行拦截。
 *
 * 【注意】验证码复用登录验证码（同一 Redis key、同一原子核销脚本）。
 * 【影响面】修改成功后该用户全部会话立即失效，必须重新登录：口令是最高凭证，改密即等于全设备下线。
 */
@Data
@Schema(description = "修改登录密码表单")
public class LoginPasswordFormDTO {

    @NotBlank(message = "原登录密码不能为空")
    @Schema(description = "原登录密码", required = true)
    private String oldPassword;

    @NotBlank(message = "新登录密码不能为空")
    @Size(min = 6, max = 32, message = "登录密码长度需为 6~32 位")
    @Schema(description = "新登录密码，6~32 位", required = true)
    private String newPassword;

    @NotBlank(message = "短信验证码不能为空")
    @Schema(description = "短信验证码（发送至账号绑定手机号，与登录验证码同一套）", required = true)
    private String code;
}
