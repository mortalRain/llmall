package com.llmall.common.constant;

/**
 * 网关与各业务服务之间的认证请求头、JWT claim 与 Redis key 约定。
 *
 * 【核心职责】把这些跨进程的字符串契约集中到一处：网关侧写 header、验签，业务侧读 header、取上下文，
 * 两侧都从本接口取常量，避免各写一份字面量。
 *
 * 【注意】这些字符串是跨进程契约，一个字母写错就是"用户莫名丢失"的线上问题：
 * 编译期不会报错，只在运行时表现为下游取不到身份、或读写落在不同的 Redis key 上。
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
     * "本请求来自网关"的凭证头（网关认证通过后注入，下游 UserInfoInterceptor 用它采信 user-info）。
     *
     * 【注意】它与 {@link #HEADER_SERVICE_TOKEN} 是两种不同的信任：本头只回答"请求有没有经过网关"，
     * 且任何登录用户的请求都带着它，因此不能用来判断"这是服务间调用"。
     */
    String HEADER_INTERNAL_TOKEN = "x-internal-token";

    /**
     * "本请求来自我们自己的服务"的凭证头，只允许服务间 Feign 调用携带。
     *
     * 【注意】必须与 {@link #HEADER_INTERNAL_TOKEN} 分开：登录态只证明"你是谁"，证明不了"你有没有付钱"；
     * 而"经过网关"证明的是"请求是用户发的"，恰恰是内部接口（如交易的"标记订单已支付"）要拒绝的那一类。
     * 网关对客户端自带的该头只剥离、不注入，外部永远伪造不了；生产环境还应配合内网隔离。
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

    /**
     * JWT 中承载签发时间的 claim（iat，秒级时间戳）。
     *
     * 【用途】"口令变更后旧令牌立即失效"需要判断"令牌是改密前还是改密后签发的"。
     * 用的是 JWT 标准 claim 名（iat），Hutool 的 {@code setIssuedAt} 会自动写入该键。
     */
    String CLAIM_IAT = "iat";

    /** Redis：令牌黑名单（登出后尚未过期的 access token 的 jti），key = 前缀 + jti，value 仅作占位 */
    String REDIS_BLACKLIST = "auth:blacklist:";

    /** Redis：refresh token 白名单（可主动失效、可轮换），key = 前缀 + jti，value = userId */
    String REDIS_REFRESH = "auth:refresh:";

    /** Redis：某用户已签发的全部 refresh jti 集合，key = 前缀 + userId（用于"登出全部设备"） */
    String REDIS_REFRESH_USER = "auth:refresh:user:";

    /** Redis：短信验证码，key = 前缀 + phone */
    String REDIS_LOGIN_CODE = "auth:code:";

    /** Redis：验证码发送频控，key = 前缀 + phone */
    String REDIS_CODE_LIMIT = "auth:code:limit:";

    /**
     * Redis：弱口令账号的缓冲期起点，key = 前缀 + userId，value = 首次被检测为弱口令的时间戳（毫秒）。
     *
     * 【为什么需要它】存量弱口令账号不能一上线就强制拦截：用户可能长期只走短信登录，
     * 突然无法登录会被判定为故障。故给一个缓冲期：期内可正常使用并持续提示改密，
     * 到期仍未修改才在登录阶段硬拦截。
     *
     * 【关联配置】本 key 的 TTL 与缓冲期长度取自用户服务侧的同一个常量，
     * 调整缓冲期时必须一并核对本 key 的 TTL，否则会出现"key 已消失而缓冲期未到"的错配。
     *
     * 【为什么起点是"首次检测"而不是"账号创建时间"】用账号创建时间会让所有存量账号上线即到期，
     * 等于没有缓冲期；本 key 记录的是"本机制首次看到该账号"的时刻，
     * 使每个存量账号都真正获得完整缓冲期（滚动生效，避免集中到期冲击客服）。
     */
    String REDIS_WEAK_PASSWORD_SINCE = "auth:weakpw:since:";

    /**
     * Redis：全局令牌失效标记，key = 前缀 + userId，value = 失效时间戳（毫秒）。
     *
     * 【用途】"改密后让该用户所有已签发的 access token 立即失效"。
     *
     * 【为什么不能用黑名单逐条拉黑】黑名单按 jti 索引，而服务端并不记录每个用户已签发过哪些 access jti
     * （access 不落白名单，只记 refresh）。逐条拉黑在实现上不可行。
     * 改用一个用户维度的"失效水位"：令牌签发时间早于水位即视为失效。
     *
     * 【取值依据】只需覆盖 access token 的最大寿命（由 llmall.jwt.tokenTTL 决定），
     * 到期后所有旧 access 自然过期，标记本身即可回收，不引入常驻 key。
     * 【关联配置】tokenTTL 调大时本 key 的 TTL 必须同步调大，
     * 否则会出现"水位已过期、旧 access 尚未过期"的窗口。
     * 【多副本】取值与 IdempotentKeys.REDIS_GLOBAL_LOGOUT_PREFIX 相同，改一处必须同步另一处。
     */
    String REDIS_GLOBAL_LOGOUT_AT = "auth:logoutat:";

    /** Redis：支付/登录口令连续错误次数，key = 前缀 + userId */
    String REDIS_PWD_FAIL_COUNT = "auth:pwdfail:";

    /** Redis：口令错误锁定标记，key = 前缀 + userId */
    String REDIS_PWD_LOCK = "auth:pwdlck:";

    /** Redis：口令修改接口的滑动窗口频控，key = 前缀 + userId */
    String REDIS_PWD_CHANGE_LIMIT = "auth:pwdchg:";

    /**
     * Redis：验证码错误次数，key = 前缀 + scene + ":" + phone。
     *
     * 【为什么按场景隔离】登录码与改密码的用途不同：若共用一个 key，拿到登录码的人
     * 可以直接完成支付口令修改，一次社会工程即可解锁资金操作。
     */
    String REDIS_CODE_FAIL = "auth:code:fail:";

    /** Redis：验证码错误达上限后的锁定标记，key = 前缀 + scene + ":" + phone */
    String REDIS_CODE_LOCK = "auth:code:lock:";

    /**
     * Redis：登录口令连续错误次数，key = 前缀 + username。
     *
     * 【为什么按 username 而不是 userId】登录失败时还不知道账号是否存在，
     * 按 userId 计数等于只保护"存在的账号"，攻击者仍可无限试探不存在的用户名。
     */
    String REDIS_LOGIN_FAIL = "auth:login:fail:";

    /** Redis：登录锁定标记，key = 前缀 + username */
    String REDIS_LOGIN_LOCK = "auth:login:lock:";
}
