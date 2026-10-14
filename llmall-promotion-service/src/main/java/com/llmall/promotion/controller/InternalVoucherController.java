package com.llmall.promotion.controller;

import io.swagger.v3.oas.annotations.enums.ParameterIn;

import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.promotion.service.IVoucherService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 营销域内部接口：只能由服务间调用（当前唯一调用方是 llmall-trade-service 的订单链路）。
 *
 * 【注意】真正的安全边界是路径可达性：/internal/** 不在网关的路由表里，外部走 8080 只会在网关层 404，
 * 只有直连服务端口的内网调用能到达这里。仅靠"带没带内部凭证头"区分不了调用方 ——
 * 网关会给每一个认证通过的外部请求注入同一个内部凭证头，否则下游 UserInfoInterceptor
 * 会把正常用户请求一起判成伪造。所以只加公开路径 + 凭证头校验是无效的。
 *
 * 【注意】路径之外仍要校验凭证头做纵深防御：服务监听 0.0.0.0，同网段/同主机进程可直连 8088 绕过网关；
 * 凭证取 llmall.internal.token，缺失时一律拒绝（fail-closed），绝不因漏配置而变成公开接口。
 *
 * orderId 另由业务层回查交易订单（存在、属于本人、门店一致、且订单上锁定的就是这张券）：
 * 路径与凭证回答"谁在调"，订单回查回答"这笔券该不该核销"。
 */
@Slf4j
@Tag(name = "营销域：内部接口（仅服务间调用，外部不可达）")
@RestController
@RequestMapping("/internal/vouchers")
@RequiredArgsConstructor
public class InternalVoucherController {

    private final IVoucherService voucherService;

    /** 内部调用凭证，与各业务服务、网关共用同一个配置项 */
    @Value("${llmall.internal.token:}")
    private String internalToken;

    @Operation(summary = "核销（锁定）券：仅供交易域下单/支付链路调用")
    @Parameters({
            @Parameter(name = "id", description = "券实例id（user_voucher.id）", in = ParameterIn.PATH, required = true),
            @Parameter(name = "orderId", description = "交易订单id（必传，用于回查订单真实性与归属）",
                    in = ParameterIn.QUERY, required = true),
            @Parameter(name = AuthConstants.HEADER_INTERNAL_TOKEN, description = "内部调用凭证",
                    in = ParameterIn.HEADER, required = true)
    })
    @PostMapping("/my/{id}/use")
    public boolean useMyVoucher(@PathVariable("id") Long id,
                               @RequestParam("orderId") Long orderId,
                               @RequestHeader(value = AuthConstants.HEADER_INTERNAL_TOKEN, required = false)
                               String requestToken) {
        assertInternalCall(requestToken);
        return voucherService.useMyVoucher(id, orderId);
    }

    @Operation(summary = "退回券：仅供交易域订单取消/退款链路调用")
    @Parameters({
            @Parameter(name = "id", description = "券实例id（user_voucher.id）", in = ParameterIn.PATH, required = true),
            @Parameter(name = "orderId", description = "交易订单id（必传，用于回查订单真实性与归属）",
                    in = ParameterIn.QUERY, required = true),
            @Parameter(name = AuthConstants.HEADER_INTERNAL_TOKEN, description = "内部调用凭证",
                    in = ParameterIn.HEADER, required = true)
    })
    @PutMapping("/my/{id}/refund")
    public boolean refundMyVoucher(@PathVariable("id") Long id,
                                   @RequestParam("orderId") Long orderId,
                                   @RequestHeader(value = AuthConstants.HEADER_INTERNAL_TOKEN, required = false)
                                   String requestToken) {
        assertInternalCall(requestToken);
        return voucherService.refundMyVoucher(id, orderId);
    }

    /**
     * 内部凭证校验：严格相等比对，缺配置或缺失请求头一律拒绝（fail-closed）。
     * 校验放在 HTTP 入口而不是 service：MQ 监听线程也会直接调 service，那里没有 HTTP 头可查。
     */
    private void assertInternalCall(String requestToken) {
        if (!StringUtils.hasText(internalToken)
                || !StringUtils.hasText(requestToken)
                || !internalToken.equals(requestToken)) {
            log.warn("拒绝非内部调用: header {} 不匹配", AuthConstants.HEADER_INTERNAL_TOKEN);
            throw new ForbiddenException("内部接口，禁止外部调用");
        }
    }
}
