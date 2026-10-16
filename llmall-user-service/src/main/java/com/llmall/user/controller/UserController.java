package com.llmall.user.controller;

import com.llmall.api.dto.MoneyDeductDTO;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.user.domain.dto.LoginFormDTO;
import com.llmall.user.domain.dto.LoginPasswordFormDTO;
import com.llmall.user.domain.dto.PayPasswordFormDTO;
import com.llmall.user.domain.dto.RefreshTokenDTO;
import com.llmall.user.domain.dto.SmsLoginDTO;
import com.llmall.user.domain.vo.PwdLockStatusVO;
import com.llmall.user.domain.vo.TokenPair;
import com.llmall.user.domain.vo.UserLoginVO;
import com.llmall.user.service.ITokenService;
import com.llmall.user.service.IUserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.Pattern;

/**
 * 统一账号入口：用户名密码登录与手机验证码登录共用同一账号体系与同一套令牌逻辑，
 * 并提供令牌刷新（轮换）、注销、口令修改与余额内部接口。
 *
 * 【读者】本类注释面向前端与运维：逐接口标注权限要求、入参约束与错误码触发条件。
 *
 * 【权限口径】只有 login、login/sms、code 三个接口匿名可访问（在网关的公开写接口白名单里显式登记）；
 * 其余接口都需登录，身份由网关解析令牌后注入 user-info 头，服务端一律不接受参数传入的 userId。
 *
 * 【错误码口径】业务拒绝为 400（响应体 msg 可直接展示），未登录 401，越权或内部接口被外部调用 403，
 * 未预期异常兜底 500。
 */
@Tag(name = "用户相关接口")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final IUserService userService;
    private final ITokenService tokenService;

    /** 服务间凭证（llmall.internal.service-token，无默认值：必须显式配置）。 */
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    /**
     * 资金内部接口的入口守卫（fail-closed）：只认 x-service-token。
     *
     * 【注意】不能用 x-internal-token 代替：那个头网关会给每个已登录用户注入，拿它判"内部调用"
     * 等于没判；x-service-token 网关只剥离、不注入，外部拿不到。服务间凭证未配置时同样拒绝。
     */
    private void assertInternalCall(String requestToken) {
        if (!StringUtils.hasText(serviceToken) || !StringUtils.hasText(requestToken)
                || !serviceToken.equals(requestToken)) {
            throw new ForbiddenException("内部接口，禁止外部调用");
        }
    }

    /**
     * 用户名密码登录。
     *
     * 【权限】匿名可访问（网关公开写接口白名单）。
     * 【入参约束】LoginFormDTO 的 username、password 均必填，违规由 @Validated 拦截。
     * 【返回】UserLoginVO：令牌对 + 用户视图 + 改密标记。
     * 【错误码】400 用户名或密码错误（两种原因不区分，避免账号枚举）；
     *          403 账号已被冻结；400 登录口令仍为初始口令且已超过缓冲期，须先修改登录口令。
     */
    @Operation(summary = "用户名密码登录")
    @PostMapping("login")
    public UserLoginVO login(@RequestBody @Validated LoginFormDTO loginFormDTO) {
        return userService.login(loginFormDTO);
    }

    /**
     * 获取当前登录用户。
     *
     * 【权限】需登录（身份来自网关注入的 user-info 头）。
     * 【返回】UserLoginVO：用户视图与改密标记，不含令牌。
     * 【错误码】401 无登录上下文或用户不存在。
     */
    @Operation(summary = "获取当前登录用户（身份由网关透传）")
    @GetMapping("/me")
    public UserLoginVO me() {
        return userService.currentUser();
    }

    /**
     * 发送手机登录验证码。
     *
     * 【权限】匿名可访问（网关公开写接口白名单）。
     * 【入参约束】phone 必填，且必须匹配 ^1[3-9]\d{9}$，违反时由 @Pattern 拦截。
     * 【错误码】400 手机号格式不正确；400 距上次发送不足发送间隔（发送过于频繁）。
     * 【注意】该验证码同时用于验证码登录与口令修改，核销是一次性的。
     */
    @Operation(summary = "发送手机验证码")
    @Parameter(name = "phone", description = "手机号", required = true)
    @PostMapping("/code")
    public void sendCode(@RequestParam("phone")
                         @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone) {
        userService.sendCode(phone);
    }

    /**
     * 手机验证码登录（手机号未注册时自动注册）。
     *
     * 【权限】匿名可访问（网关公开写接口白名单）。
     * 【入参约束】SmsLoginDTO 的 phone、code 均必填；phone 匹配 ^1[3-9]\d{9}$、code 为 6 位数字，
     *          且 phone 在 service 层按同一正则再校验一次。
     * 【返回】UserLoginVO：令牌对 + 用户视图 + 改密标记。
     * 【错误码】400 手机号为空或格式不正确；400 验证码为空、非 6 位数字、已过期、错误或已被使用；
     *          403 账号已被冻结；400 登录口令仍为初始口令且已超过缓冲期。
     */
    @Operation(summary = "手机验证码登录（未注册自动注册）")
    @PostMapping("/login/sms")
    public UserLoginVO loginByCode(@RequestBody @Validated SmsLoginDTO smsLoginDTO) {
        return userService.loginByCode(smsLoginDTO);
    }

    /**
     * 刷新令牌：refresh token 从请求头 x-refresh-token 或请求体传入（请求头优先）。
     *
     * 【权限】需登录（网关鉴权；access 已过期但 refresh 有效时，网关会先无感换发，前端无需手动续期）。
     * 【入参约束】两处都未携带 refresh token 时按缺失处理。
     * 【返回】新令牌对；轮换策略：调用成功后旧 refresh 立即失效，必须保存并使用返回的新令牌对。
     * 【错误码】401 缺少刷新令牌、令牌类型不是 refresh、refresh 已过期、已轮换或已作废、
     *          签发时间早于会话失效水位（改密后）。
     */
    @Operation(summary = "刷新令牌（轮换）")
    @PostMapping("/token/refresh")
    public TokenPair refresh(
            @RequestHeader(value = AuthConstants.HEADER_REFRESH_TOKEN, required = false) String headerToken,
            @RequestBody(required = false) RefreshTokenDTO body) {
        String refreshToken = headerToken;
        if (refreshToken == null && body != null) {
            refreshToken = body.getRefreshToken();
        }
        return tokenService.refresh(refreshToken);
    }

    /**
     * 注销：access token 拉黑 + 作废该用户全部 refresh（登出所有设备）。
     *
     * 【权限】需登录。
     * 【返回】无返回值；幂等：令牌已过期或不可用时同样按成功处理，不抛异常。
     */
    @Operation(summary = "注销登录")
    @PostMapping("/logout")
    public void logout(@RequestHeader(value = AuthConstants.HEADER_AUTHORIZATION, required = false) String authorization) {
        tokenService.logout(authorization);
    }

    /**
     * 修改/设置支付密码。
     *
     * 【权限】需登录。
     * 【入参约束】PayPasswordFormDTO 三个字段均必填，新口令长度 6~32 位。
     * 【返回】无返回值。
     * 【错误码】401 未登录；400 请求过于频繁；400 账号处于口令锁定中（提示语带剩余分钟数）；
     *          400 验证码为空/已过期/错误/已被使用；400 原支付口令错误；400 新口令与原口令相同。
     * 【影响面】成功后该用户全部会话立即失效，前端须清本地令牌并引导重新登录。
     * 【配套关系】本接口是"仍在使用初始支付口令时拒绝余额支付"的配套闭环：
     *          没有它，被拦截的用户无法完成支付。
     */
    @Operation(summary = "修改/设置支付密码（需登录；校验原支付密码 + 短信验证码）")
    @PostMapping("/pay-password")
    public void changePayPassword(@RequestBody @Validated PayPasswordFormDTO form) {
        userService.changePayPassword(form);
    }

    /**
     * 修改登录密码。
     *
     * 【权限】需登录。
     * 【入参约束】LoginPasswordFormDTO 三个字段均必填，新口令长度 6~32 位。
     * 【返回】无返回值。
     * 【错误码】401 未登录；400 请求过于频繁；400 账号处于口令锁定中（提示语带剩余分钟数）；
     *          400 验证码为空/已过期/错误/已被使用；400 原登录口令错误；400 新口令与原口令相同。
     * 【影响面】成功后该用户全部会话立即失效，前端须清本地令牌并引导重新登录。
     * 【配套关系】本接口是弱口令收口的落地通道：没有它，登录响应里的改密标记只能提示不能落地。
     */
    @Operation(summary = "修改登录密码（需登录；校验原登录密码 + 短信验证码）")
    @PostMapping("/password")
    public void changeLoginPassword(@RequestBody @Validated LoginPasswordFormDTO form) {
        userService.changeLoginPassword(form);
    }

    /**
     * 查询支付密码锁定状态。
     *
     * 【权限】需登录，只能查自己的状态（userId 取自网关注入的身份，不接受参数传入）。
     * 【入参约束】无。
     * 【返回】locked=false 且 remainMinutes=0 表示未锁定；locked=true 时 remainMinutes 为剩余分钟数
     *          （向上取整，最小 1）。用数据表达状态，不消耗错误码；返回值不含已错误次数。
     * 【错误码】401 未登录。
     * 【使用场景】前端进入支付页/改密页时先查一次：已锁定则置灰提交按钮并展示剩余时长，
     *          避免用户反复提交后才撞上锁定提示。
     */
    @Operation(summary = "查询支付密码锁定状态（需登录；供前端预判并展示剩余锁定时长）")
    @GetMapping("/pay-password/lock")
    public PwdLockStatusVO payPasswordLockStatus() {
        return userService.queryPayPasswordLockStatus();
    }

    /**
     * 扣减余额（内部接口，由 llmall-pay-service 调用）。
     *
     * 【权限】仅服务间调用：必须携带服务间凭证 x-service-token，匿名与已登录用户均不可调。
     * 【入参约束】请求体 MoneyDeductDTO：pw 支付口令、amount 单位为分且必须大于 0、bizNo 幂等键必填。
     * 【返回】无返回值。
     * 【错误码】403 未携带或携带错误的服务间凭证（含服务端未配置凭证的情况）；
     *          400 金额非法、bizNo 为空、支付口令错误、余额不足、账号仍在使用初始支付口令。
     * 【注意】密码只走请求体、不进 URL：URL 会被 Feign 的 FULL 日志、access log 与链路追踪记录。
     * 【影响面】仍在使用初始支付口令时本接口拒绝扣款，调用方应把该业务错误原文透传给前端，
     *          不要归一成"支付失败"。
     */
    @Operation(summary = "扣减余额（内部接口：仅服务间可调，密码走请求体）")
    @PostMapping("/money/deduct")
    public void deductMoney(@RequestBody @Validated MoneyDeductDTO deductDTO,
                            @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        userService.deductMoney(deductDTO.getPw(), deductDTO.getAmount(), deductDTO.getBizNo());
    }

    /**
     * 退款入账（内部接口，必须携带服务间凭证 x-service-token，见 {@link #assertInternalCall}）。
     *
     * 【权限】仅服务间调用，匿名与已登录用户均不可调。
     * 【入参约束】userId 由调用方按自己库里的订单归属人传入（不取登录态）；
     *          amount 单位为分且必须大于 0；bizNo 为幂等键，必填。
     * 【返回】无返回值；同一业务号重复调用只入账一次。
     * 【错误码】403 未携带或携带错误的服务间凭证；400 userId/amount/bizNo 为空或不合法、目标用户不存在。
     * 【注意】userId 由调用方传入而不是从登录态取：交易/营销/支付域的退款闭环在 MQ 或后台线程里
     *          没有登录态，且按登录态取 userId 会让任意登录用户给自己加钱。
     */
    @Operation(summary = "退款入账（内部接口：仅服务间可调，把余额加回指定用户）")
    @Parameters({
            @Parameter(name = "userId", description = "收款用户id（由交易域按订单归属人传入）", required = true),
            @Parameter(name = "amount", description = "退款金额（分），必须大于 0", required = true),
            @Parameter(name = "bizNo", description = "幂等键（必填）：ORDER_REFUND:<订单id> / PAYFAIL:<扣款业务号> / "
                    + "SECKILL_REFUND:<token>；同一业务号重复调用只入账一次", required = true)
    })
    @PostMapping("/money/refund")
    public void refundMoney(@RequestParam("userId") Long userId, @RequestParam("amount") Integer amount,
                            @RequestParam("bizNo") String bizNo,
                            @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        userService.refundMoney(userId, amount, bizNo);
    }
}
