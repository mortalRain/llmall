package com.llmall.common.domain;

import lombok.Data;

import java.util.Date;

/**
 * 令牌载荷，网关与用户服务共用同一契约，避免两侧各存一份而字段对不上。
 */
@Data
public class TokenPayload {

    private Long userId;

    /** 令牌唯一编号，主动失效与黑名单的抓手 */
    private String jti;

    /** 令牌类型：access / refresh */
    private String type;

    private Date expiresAt;

    /** 距过期剩余秒数，用于设置黑名单 TTL */
    public long remainSeconds() {
        if (expiresAt == null) {
            return 0L;
        }
        long remain = (expiresAt.getTime() - System.currentTimeMillis()) / 1000;
        return Math.max(remain, 0L);
    }
}
