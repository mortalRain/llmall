package com.llmall.shop.controller;

import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.shop.service.IShopStaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店域内部接口：只允许服务间调用，不暴露给外部。
 *
 * 【为什么单独一个 Controller】{@link ShopController} 的路径全部挂在 /shops 下，其中多数是
 * 匿名可读的展示接口；把"判权"这种安全语义混进去，很容易在调整网关白名单时被顺手放行。
 * 独立到 /internal/** 后：网关路由表里没有 /internal/**（外部根本到不了），即使将来误加路由，
 * 本类仍要求 {@code x-service-token}（网关只剥离、不注入），外部拿不到。
 *
 * 【鉴权口径】与交易的 {@code PUT /orders/{id}}（标记已支付）保持一致：
 * 只认 {@code llmall.internal.service-token}，且**配置缺失时同样拒绝**（fail-closed）——
 * 不能因为"运维忘了配"就让内部接口变成公开接口。
 *
 * 【注意】不要用 {@code x-internal-token} 判"内部调用"：网关在认证通过后会给每个登录用户
 * 注入该头，任何登录用户的请求都带着它，拿它判内部调用等于没判。
 */
@Tag(name = "门店内部接口（服务间调用）")
@RestController
@RequestMapping("/internal/shops")
@RequiredArgsConstructor
public class InternalShopController {

    private final IShopStaffService shopStaffService;

    /** 服务间调用凭证（llmall.internal.service-token）：故意不给默认值，配置缺失时必须让校验失败 */
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    /**
     * 判断用户是否为门店员工：交易域核销前的唯一权限依据。
     *
     * 【返回 boolean 而不是抛异常】调用方（交易域）需要区分"不是员工"（403 拒绝核销）与
     * "门店服务不可用"（同样拒绝，但要打 ERROR 让运维介入）；返回布尔值让调用方自己决定语义，
     * 异常只留给"未授权访问内部接口"这一种情况。
     *
     * 【fail-closed】参数缺失时 isStaff 返回 false；本接口自身不返回 true 兜底。
     */
    @Operation(summary = "内部接口：判断用户是否为指定门店的员工（需服务间凭证）")
    @Parameter(name = "shopId", description = "门店id", in = ParameterIn.PATH, required = true)
    @Parameter(name = "userId", description = "用户id", in = ParameterIn.PATH, required = true)
    @GetMapping("/{shopId}/staff/{userId}")
    public boolean isStaff(@PathVariable("shopId") Long shopId,
                           @PathVariable("userId") Long userId,
                           @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false)
                           String requestToken) {
        if (!StringUtils.hasText(serviceToken)
                || !StringUtils.hasText(requestToken)
                || !serviceToken.equals(requestToken)) {
            // 配置缺失时同样拒绝：不能因为"运维忘了配"而让判权接口变成公开接口
            throw new ForbiddenException("内部接口，禁止外部调用");
        }
        return shopStaffService.isStaff(shopId, userId);
    }
}
