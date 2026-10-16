package com.llmall.user.service;

import com.llmall.user.domain.vo.TokenPair;

/**
 * 令牌服务契约：签发 / 刷新 / 注销 / 全量会话失效。
 *
 * 令牌生命周期（白名单、黑名单、轮换、失效水位）与用户业务是两个关注点，单独抽出。
 *
 * 【契约与实现的边界】本接口只写功能、入参与异常；水位的取值依据、关联配置与失败取向
 * 见 TokenServiceImpl。
 */
public interface ITokenService {

    /** 为指定用户签发 access + refresh 令牌对。 */
    TokenPair issue(Long userId);

    /**
     * 用 refresh token 换取新的令牌对，旧 refresh 立即失效（轮换防重放）。
     *
     * 【异常】com.llmall.common.exception.UnauthorizedException：refresh 缺失、非法、已过期、
     *        已轮换或已作废，以及签发时间早于会话失效水位（改密后）。
     */
    TokenPair refresh(String refreshToken);

    /**
     * 注销：把 access token 拉黑，并作废该用户全部 refresh token（登出所有设备）。
     *
     * 【返回】无返回值；幂等：access 已过期或不可用时同样不抛异常。
     */
    void logout(String accessToken);

    /**
     * 口令变更后的全量会话失效：作废该用户全部 refresh，并使其此前签发的 access 立即失效。
     *
     * 【入参约束】userId 为目标用户；为 null 时直接返回。
     * 【返回】无返回值；内部任一步失败只记日志，不抛异常（口令已经改成功，不能因会话清理失败而回滚）。
     * 【注意】只在口令/凭证变更时调用：修改支付口令、修改登录口令、账号冻结、管理员踢人。
     *        不得用于日常业务，它会让该用户所有设备立即掉线。
     *
     * @param userId 目标用户
     */
    void invalidateAllSessions(Long userId);
}
