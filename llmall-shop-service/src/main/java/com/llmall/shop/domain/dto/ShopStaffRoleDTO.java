package com.llmall.shop.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 修改门店员工角色请求体：{@code PUT /shops/{shopId}/staff/{userId}}。
 *
 * 单独一个 DTO 而不是复用 {@link ShopStaffAddDTO}：修改接口的 userId 在路径里，
 * 请求体只该有 role；复用会让调用方误以为改角色还能顺便换人。
 */
@Data
public class ShopStaffRoleDTO {

    @NotBlank(message = "role 不能为空")
    private String role;
}
