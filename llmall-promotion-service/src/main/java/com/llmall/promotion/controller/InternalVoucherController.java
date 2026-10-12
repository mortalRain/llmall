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
 * 营销域**内部接口**：只能由服务间调用（当前唯一调用方是 llmall-trade-service 的订单链路）。
 *
 * <h3>为什么把接口放在 /internal 前缀下（Z4 的关键）</h3>
 * 券核销/退券会改变"用户资产 + 平台让利"的事实，必须由订单链路驱动。
 * 只加一个请求头校验是不够的：网关会给**每一个认证通过的外部请求**注入同一个内部凭证头
 * （否则下游的 UserInfoInterceptor 会连正常用户请求一起判成伪造），
 * 所以"外部请求带没带 x-internal-token"这件事本身区分不了调用方。
 * 真正可靠的边界是**路径可达性**：/internal/** 不在网关的路由表里，
 * 外部走 8080 只会在网关层 404，只有直连服务端口的内网调用能到达这里。
 *
 * <h3>为什么路径之外还要校验凭证头</h3>
 * 纵深防御：服务监听 0.0.0.0，同网段/同主机上的进程可以直接连 8088 绕过网关。
 * 凭证在配置里（llmall.internal.token），并配合内网隔离才算完整；
 * 一旦凭证缺失（运维漏配）也一律拒绝（fail-closed），绝不因为"没配置"而变成公开接口。
 *
 * <h3>为什么还要把 orderId 一路带下去做校验</h3>
 * 即使调用方是受信的交易域，营销域也不会"相信调用方说的订单"：
 * 业务层会拿 orderId 回查交易订单（存在、属于本人、门店一致、且订单上锁定的就是这张券）。
 * 两道防线负责的是不同的事：路径/凭证回答"谁在调"，订单回查回答"这笔券该不该核销"。
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
     * 把校验放在 HTTP 入口而不是 service 里：MQ 监听线程也会直接调 service，
     * 那里没有 HTTP 头可查，边界画在"HTTP 入口"才既挡得住外部、又不误伤内部异步链路。
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
