package com.llmall.common.domain;

import lombok.Data;

import java.util.Date;

/**
 * 令牌载荷（网关与用户服务共用同一契约）。
 *
 * 放在 llmall-common 而不是各自模块里：网关要解析、用户服务要签发的字段必须完全一致，
 * 两份拷贝迟早会因为"只改一边"而对不上（这是微服务里最典型的隐性 bug 来源）。
 */
@Data
public class TokenPayload {

    /** 用户 id */
    private Long userId;

    /** 令牌唯一编号（主动失效 / 黑名单的抓手） */
    private String jti;

    /** 令牌类型：access / refresh */
    private String type;

    /** 过期时间 */
    private Date expiresAt;

    /** 距过期还有多少秒（用于设置黑名单 TTL） */
    public long remainSeconds() {
        if (expiresAt == null) {
            return 0L;
        }
        long remain = (expiresAt.getTime() - System.currentTimeMillis()) / 1000;
        return Math.max(remain, 0L);
    }
}
