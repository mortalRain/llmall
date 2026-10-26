package com.llmall.shop.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 新增门店员工请求体：{@code POST /shops/{shopId}/staff}。
 *
 * 【注意】userId 是 llmall-user.user.id（跨库逻辑外键），不是用户名；
 * 用户域是否真实存在该 id 由前端/调用方保证，门店域不跨库做存在性校验。
 * role 的合法性在 Service 层用 {@code ShopStaffRole#require} 解析（大小写不敏感）。
 */
@Data
public class ShopStaffAddDTO {

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @NotBlank(message = "role 不能为空")
    private String role;
}
