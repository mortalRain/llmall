package com.llmall.user.controller;

import com.llmall.api.dto.UserProfileDTO;
import com.llmall.api.dto.UserProfileQueryDTO;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 用户域内部接口：只允许服务间调用，不暴露给外部。
 *
 * 【为什么单独一个 Controller】与门店域的 InternalShopController 同一口径：网关路由表里没有
 * /internal/**（外部根本到不了），即使将来误加路由，本类仍要求 {@code x-service-token}
 * （网关只剥离、不注入），外部拿不到。
 *
 * 【鉴权口径】只认 {@code llmall.internal.service-token}，且配置缺失时同样拒绝（fail-closed）——
 * 不能因为"运维忘了配"就让内部接口变成公开接口。
 *
 * 【数据边界】本类只返回昵称/头像：内容域展示评价作者不需要手机号、余额、口令，
 * 这些字段一律不得进入响应体（也由 UserProfileDTO 的字段白名单兜住）。
 */
@Tag(name = "用户内部接口（服务间调用）")
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final IUserService userService;

    /** 服务间调用凭证（llmall.internal.service-token）：故意不给默认值，配置缺失时必须让校验失败 */
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    /**
     * 批量查询用户展示资料（评价列表一次性回填作者昵称/头像，避免 N+1）。
     *
     * 【入参】{@code {"ids":[1,2,3]}}；空/缺省返回空数组。
     * 【返回】只含 id / nickname / icon；nickname、icon 可能为 null。
     * 【错误码】403 未携带或携带错误的服务间凭证（含服务端未配置凭证的情况）。
     */
    @Operation(summary = "内部接口：批量查询用户昵称/头像（需服务间凭证）")
    @PostMapping("/profiles")
    public List<UserProfileDTO> profiles(
            @RequestBody(required = false) UserProfileQueryDTO query,
            @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        if (query == null || query.getIds() == null || query.getIds().isEmpty()) {
            return Collections.emptyList();
        }
        return userService.queryProfiles(query.getIds());
    }

    /**
     * 内部调用入口守卫（fail-closed）：只认 x-service-token。
     *
     * 【注意】不能用 x-internal-token 代替：网关会给每个已登录用户注入该头，
     * 拿它判"内部调用"等于没判；x-service-token 网关只剥离、不注入，外部拿不到。
     */
    private void assertInternalCall(String requestToken) {
        if (!StringUtils.hasText(serviceToken) || !StringUtils.hasText(requestToken)
                || !serviceToken.equals(requestToken)) {
            throw new ForbiddenException("内部接口，禁止外部调用");
        }
    }
}
