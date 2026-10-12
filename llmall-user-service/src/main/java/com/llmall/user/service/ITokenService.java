package com.llmall.user.service;

import com.llmall.user.domain.vo.TokenPair;

/**
 * 令牌服务：签发 / 刷新 / 注销。
 *
 * 单独抽出来的原因：令牌的生命周期（Redis 白名单、黑名单、轮换）是与"用户业务"不同的关注点，
 * 混在 UserServiceImpl 里会让登录方法变成一坨。这也是面试可以讲的"职责分离"。
 */
public interface ITokenService {

    /** 为指定用户签发 access + refresh 令牌对 */
    TokenPair issue(Long userId);

    /**
     * 用 refresh token 换取新的令牌对（轮换：旧 refresh 立即失效，防止被重放）
     *
     * @throws com.llmall.common.exception.UnauthorizedException refresh 无效或已失效
     */
    TokenPair refresh(String refreshToken);

    /**
     * 注销：把 access token 拉黑，并作废该用户全部 refresh token（登出所有设备）
     * access token 已过期时静默返回，不抛异常（注销应当是幂等的）
     */
    void logout(String accessToken);
}
