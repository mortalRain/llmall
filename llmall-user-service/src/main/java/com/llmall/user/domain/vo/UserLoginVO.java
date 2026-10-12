package com.llmall.user.domain.vo;

import lombok.Data;

@Data
public class UserLoginVO {
    /** 兼容旧前端：等同于 accessToken */
    private String token;

    /** 访问令牌（短） */
    private String accessToken;

    /** 刷新令牌（长），用于 access 过期后无感换新 */
    private String refreshToken;

    private Long userId;
    private String username;
    private String nickName;
    private String icon;
    private Integer balance;

    /** 积分（来自 user_info） */
    private Integer credits;

    /** 会员等级（来自 user_info） */
    private Integer level;
}
