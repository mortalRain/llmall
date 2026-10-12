package com.llmall.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 刷新令牌请求体（同时也支持用请求头 x-refresh-token 传递）。
 */
@Data
@Schema(description = "刷新令牌请求体")
public class RefreshTokenDTO {

    @Schema(description = "刷新令牌")
    private String refreshToken;
}
