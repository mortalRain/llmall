package com.llmall.user.domain.vo;

import lombok.Data;

@Data
public class UserLoginVO {
    /** 等同于 accessToken，仅为兼容旧前端保留 */
    private String token;

    /** 访问令牌（寿命短，过期后由网关用 refresh 换发） */
    private String accessToken;

    /** 刷新令牌（寿命长），用于 access 过期后换新 */
    private String refreshToken;

    private Long userId;
    private String username;
    private String nickName;
    private String icon;

    /** 余额（单位：分） */
    private Integer balance;

    /** 积分（来自 user_info 表） */
    private Integer credits;

    /** 会员等级（来自 user_info 表） */
    private Integer level;

    /**
     * 是否仍在使用初始支付口令：true = 调用方应引导用户先修改支付密码。
     *
     * 【安全】只回传布尔值，绝不回传口令或其任何片段：它由服务端比对当前口令哈希与初始口令得出，
     * 调用方无法从中反推口令。
     */
    private Boolean needChangePayPassword;

    /** 是否仍在使用初始登录口令：true = 调用方应引导用户先修改登录密码 */
    private Boolean needChangeLoginPassword;

    /**
     * 强制改密标记：登录口令或支付口令任一仍为初始值时返回 true。
     *
     * 【为什么用响应体标记而不是状态码】用非 2xx 表达"登录成功但需改密"会让前端把成功登录判成失败
     * （并可能清掉刚拿到的令牌）：登录照常成功、令牌照常下发，改密引导交给调用方。
     *
     * 【字段关系】本字段是上面两个细分字段的汇总，具体是哪一项由它们区分，便于前端给出精确文案。
     */
    private Boolean needChangePassword;
}
