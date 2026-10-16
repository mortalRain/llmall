package com.llmall.common.domain;

import lombok.Data;

import java.util.Date;

/**
 * 令牌载荷：解析成功后的令牌身份信息，网关与用户服务共用同一契约，避免两侧各存一份而字段对不上。
 *
 * 【边界约束】userId/jti/type 由解析入口保证非空，其余字段可为 null：
 * expiresAt 为 null 只在本类被单独构造时出现 —— parse 与 parseAllowExpired 都拒绝没有 exp 的令牌；
 * issuedAt 为 null 表示令牌没有 iat，网关据此跳过水位校验而不是拒绝。
 *
 * 【注意】type 只有 access / refresh 两个取值，它的用途是阻止 refresh 令牌直接访问业务接口。
 */
@Data
public class TokenPayload {

    private Long userId;

    /** 令牌唯一编号，主动失效与黑名单的抓手 */
    private String jti;

    /** 令牌类型：access / refresh */
    private String type;

    private Date expiresAt;

    /**
     * 令牌签发时间（iat），用于"口令变更后旧令牌立即失效"的判定。
     *
     * 【为什么需要它】改密后要做到"所有设备立即下线"，必须能判断"这个令牌是改密之前还是之后签发的"。
     * 仅靠黑名单不可行 —— 黑名单按 jti 索引，而服务端并不记录每个用户已签发的全部 access jti。
     * 有了 iat，只需一个用户维度的时间水位即可完成判定。
     *
     * 【兼容性】存量令牌可能没有 iat（由尚未写入 iat 的签发端签发），此时该字段为 null，
     * 网关会跳过水位校验（而不是拒绝）：否则升级瞬间所有在用令牌会集体失效，
     * 表现为"全站用户被强制登出"。代价是这些旧令牌无法被水位机制召回，它们最迟在剩余寿命内自然过期。
     */
    private Date issuedAt;

    /** 无效令牌（无 exp）的剩余寿命标记：区别于"恰好已过期"，调用方据此拒绝而不是静默跳过 */
    public static final long REMAIN_INVALID = -1L;

    /**
     * 距过期剩余秒数，用于设置黑名单 TTL。
     *
     * 【注意】无 exp 时返回 {@link #REMAIN_INVALID}（-1），不能返回 0：
     * 调用方（logout）的判据是"剩余寿命 > 0 才拉黑"，返回 0 会让"无 exp 的令牌"既不写黑名单、
     * 也不作废 refresh —— 而 hutool 对无 exp 的令牌既不报错也不判过期，两者叠加就是
     * "永不过期且无法撤销"的令牌。返回 -1 使调用方能够显式区分并拒绝。
     */
    public long remainSeconds() {
        if (expiresAt == null) {
            return REMAIN_INVALID;
        }
        long remain = (expiresAt.getTime() - System.currentTimeMillis()) / 1000;
        // 已过期返回 0（不是 -1）：调用方据此跳过黑名单（已过期的令牌无需拉黑）而不是当成非法令牌
        return Math.max(remain, 0L);
    }
}
