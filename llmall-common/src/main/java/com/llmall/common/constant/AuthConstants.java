package com.llmall.common.constant;

/**
 * 认证相关的统一约定。
 *
 * 为什么要有这个类：网关（写 header、验签）与各业务服务（读 header、取上下文）
 * 必须使用完全一致的字符串，否则一个字母写错就是"用户莫名丢失"的线上问题。
 * 把魔法字符串集中到一处，是微服务间契约治理的基本功。
 */
public interface AuthConstants {

    /** 网关写入、下游读取的当前用户头（值为 userId） */
    String HEADER_USER_INFO = "user-info";

    /** 客户端携带 access token 的请求头 */
    String HEADER_AUTHORIZATION = "authorization";

    /** 客户端携带 refresh token 的请求头（用于网关无感刷新） */
    String HEADER_REFRESH_TOKEN = "x-refresh-token";

    /** 网关刷新成功后，把新的 access token 通过该响应头回给客户端 */
    String HEADER_NEW_ACCESS_TOKEN = "x-access-token";

    /**
     * "本请求来自网关"的凭证头（网关在认证通过后注入，下游 UserInfoInterceptor 用它采信 user-info）。
     * 注意它与 {@link #HEADER_SERVICE_TOKEN} 是**两种不同的信任**：
     * 这个头回答的是"请求有没有经过网关"，网关注入它，所以**任何登录用户的请求都带着它** ——
     * 因此它不能用来判断"这是服务间调用"。
     */
    String HEADER_INTERNAL_TOKEN = "x-internal-token";

    /**
     * "本请求来自我们自己的服务"的凭证头 —— **只允许服务间 Feign 调用携带**。
     *
     * <p>为什么必须与 {@link #HEADER_INTERNAL_TOKEN} 分开：有些接口在网关层是"登录即可访问"的，
     * 但它本身**不是给用户用的**（典型例子：交易的"标记订单已支付"）。登录态只能证明"你是谁"，
     * 证明不了"你有没有付钱"；而"经过网关"证明的是"请求是用户发的"，
     * 恰恰是我们要拒绝的那一类。所以内部接口需要第三种凭证：只有真正的服务调用方持有它。
     *
     * <p>约定：网关对客户端自带的该头**只剥离、不注入**（外部永远伪造不了），
     * 生产环境还应配合内网隔离；示例里的固定值仅用于本地演示。
     */
    String HEADER_SERVICE_TOKEN = "x-service-token";

    /** 令牌类型：访问令牌 */
    String TOKEN_TYPE_ACCESS = "access";

    /** 令牌类型：刷新令牌 */
    String TOKEN_TYPE_REFRESH = "refresh";

    /** JWT 中承载用户 id 的 claim */
    String CLAIM_USER = "user";

    /** JWT 中承载令牌唯一编号的 claim（主动失效靠它） */
    String CLAIM_JTI = "jti";

    /** JWT 中承载令牌类型的 claim */
    String CLAIM_TYPE = "type";

    /** Redis：令牌黑名单（登出后未过期的 access token 摘要），key = 前缀 + jti */
    String REDIS_BLACKLIST = "auth:blacklist:";

    /** Redis：refresh token 白名单（可主动失效、可轮换），key = 前缀 + jti，value = userId */
    String REDIS_REFRESH = "auth:refresh:";

    /** Redis：某用户已签发的全部 refresh jti 集合，key = 前缀 + userId（用于"登出全部设备"） */
    String REDIS_REFRESH_USER = "auth:refresh:user:";

    /** Redis：短信验证码，key = 前缀 + phone */
    String REDIS_LOGIN_CODE = "auth:code:";

    /** Redis：验证码发送频控，key = 前缀 + phone */
    String REDIS_CODE_LIMIT = "auth:code:limit:";
}
