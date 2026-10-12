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
 * 统一账号入口。
 *
 * 两条登录通道（用户名密码 / 手机验证码）+ 令牌刷新 + 注销，
 * 全部走同一个账号体系与同一套令牌逻辑。
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
     * <p>为什么不能用 x-internal-token 代替：那个头网关会给**每个已登录用户**注入，
     * 拿它判"内部调用"等于没判；x-service-token 网关只剥离、不注入，外部拿不到。
     * 配置缺失时同样拒绝 —— "运维忘了配"不该等于"接口对外开放"。
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
     * 扣减余额（llmall-pay-service → 本接口，内部调用）。
     *
     * <p>【安全修复：密码不再走 URL】原实现是
     * {@code PUT /users/money/deduct?pw=123456&amount=25600}，密码出现在请求行里，
     * 结果是被 Feign 的 FULL 日志、access log、链路追踪反复记录下来（实测日志可 grep 到明文）。
     * 现在改成 POST + 请求体：密码只存在于 body，不进 URL、不进 access log，
     * 也能随着日志级别调整整体关闭。HTTP 语义上也更正确 —— 这是"创建一笔扣款"的写操作。
     */
    @Operation(summary = "扣减余额（内部接口：仅服务间可调，密码走请求体）")
    @PostMapping("/money/deduct")
    public void deductMoney(@RequestBody @Validated MoneyDeductDTO deductDTO,
                            @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        userService.deductMoney(deductDTO.getPw(), deductDTO.getAmount());
    }

    /**
     * 退款入账（内部接口）。
     *
     * <p>调用方是交易/营销/支付域的退款闭环，userId 由调用方按自己库里的订单归属人传入，
     * 而不是从登录态取 —— 这些域在 MQ/后台线程里退款时没有登录态。
     *
     * <p><b>越权防线在入口</b>：必须携带服务间凭证 x-service-token（{@link #assertInternalCall}）。
     * 只靠"退给上下文用户本人"是不够的 —— 用户请求带的是**自己**的上下文，
     * 于是任意登录用户都能给自己加钱（实测：登录后一条 POST 即可增加余额）。
     */
    @Operation(summary = "退款入账（内部接口：仅服务间可调，把余额加回指定用户）")
    @Parameters({
            @Parameter(name = "userId", description = "收款用户id（由交易域按订单归属人传入）", required = true),
            @Parameter(name = "amount", description = "退款金额（分），必须大于 0", required = true)
    })
    @PostMapping("/money/refund")
    public void refundMoney(@RequestParam("userId") Long userId, @RequestParam("amount") Integer amount,
                            @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        userService.refundMoney(userId, amount);
    }
}
