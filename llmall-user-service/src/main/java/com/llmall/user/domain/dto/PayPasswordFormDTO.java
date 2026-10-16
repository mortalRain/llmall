package com.llmall.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 修改/设置支付密码表单（供余额支付使用）。
 *
 * 【两种校验不可互相替代】原支付密码证明操作者知道当前支付口令，短信验证码证明操作者持有
 * 账号绑定的手机号：前者防"会话令牌被盗后静默改密"，后者防"账号被完全接管后连支付口令一起改掉"。
 *
 * 【边界约束】初始口令账号是用本表单"设置"口令（原口令即初始值），服务端不区分设置与修改，
 * 对外只有一个接口，调用方无需判断账号状态。
 *
 * 【注意】验证码与登录验证码是同一套（同一 Redis key，同一原子核销脚本），
 * 因此调用本接口会消耗掉本次短信验证码；三个字段均必填，由 @NotBlank 等注解先行拦截。
 */
@Data
@Schema(description = "修改/设置支付密码表单")
public class PayPasswordFormDTO {

    @NotBlank(message = "原支付密码不能为空")
    @Schema(description = "原支付密码（初始密码即账号创建时的默认支付口令）", required = true)
    private String oldPassword;

    @NotBlank(message = "新支付密码不能为空")
    @Size(min = 6, max = 32, message = "支付密码长度需为 6~32 位")
    @Schema(description = "新支付密码，6~32 位", required = true)
    private String newPassword;

    @NotBlank(message = "短信验证码不能为空")
    @Schema(description = "短信验证码（发送至账号绑定手机号，与登录验证码同一套）", required = true)
    private String code;
}
