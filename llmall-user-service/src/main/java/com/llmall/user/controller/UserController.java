package com.llmall.user.controller;

import com.llmall.api.dto.MoneyDeductDTO;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.user.domain.dto.LoginFormDTO;
import com.llmall.user.domain.dto.RefreshTokenDTO;
import com.llmall.user.domain.dto.SmsLoginDTO;
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
 * 并提供令牌刷新（轮换）与注销。
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

    @Operation(summary = "用户名密码登录")
    @PostMapping("login")
    public UserLoginVO login(@RequestBody @Validated LoginFormDTO loginFormDTO) {
        return userService.login(loginFormDTO);
    }

    @Operation(summary = "获取当前登录用户（身份由网关透传）")
    @GetMapping("/me")
    public UserLoginVO me() {
        return userService.currentUser();
    }

    @Operation(summary = "发送手机验证码")
    @Parameter(name = "phone", description = "手机号", required = true)
    @PostMapping("/code")
    public void sendCode(@RequestParam("phone")
                         @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone) {
        userService.sendCode(phone);
    }

    @Operation(summary = "手机验证码登录（未注册自动注册）")
    @PostMapping("/login/sms")
    public UserLoginVO loginByCode(@RequestBody @Validated SmsLoginDTO smsLoginDTO) {
        return userService.loginByCode(smsLoginDTO);
    }

    /**
     * 刷新令牌：refresh token 从请求头 x-refresh-token 或请求体传入。
     * 轮换策略：调用成功后旧 refresh 立即失效，必须使用返回的新令牌对。
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
     * 幂等：令牌已过期、已失效时同样返回成功。
     */
    @Operation(summary = "注销登录")
    @PostMapping("/logout")
    public void logout(@RequestHeader(value = AuthConstants.HEADER_AUTHORIZATION, required = false) String authorization) {
        tokenService.logout(authorization);
    }

    /**
     * 扣减余额（llmall-pay-service → 本接口，内部调用，必须携带服务间凭证 x-service-token）。
     *
     * 【注意】密码只走请求体、不进 URL：URL 会被 Feign 的 FULL 日志、access log 与链路追踪记录。
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
     * userId 由调用方按自己库里的订单归属人传入，而不是从登录态取：交易/营销/支付域的退款闭环
     * 在 MQ 或后台线程里退款时没有登录态，且按登录态取 userId 会让任意登录用户给自己加钱。
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
