package com.llmall.user.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 弱口令缓冲期策略的单元测试。
 *
 * 【最重要的一条】key 的 TTL 必须长于缓冲期：两者相等时 key 会先于业务期限消失，
 * 下一次登录又被当成"首次检测"重新计时，到期硬拦截永远不会触发。
 */
class WeakPasswordPolicyTest {

    @Test
    void ttlMustCoverGrace() {
        assertThat(WeakPasswordPolicy.ttlCoversGrace()).isTrue();
        assertThat(WeakPasswordPolicy.TTL).isGreaterThan(WeakPasswordPolicy.GRACE);
    }

    @Test
    void notExpiredInsideGrace() {
        long now = System.currentTimeMillis();
        long sixDaysAgo = now - Duration.ofDays(6).toMillis();
        assertThat(WeakPasswordPolicy.isExpired(sixDaysAgo, now)).isFalse();
    }

    @Test
    void expiredAfterGrace() {
        long now = System.currentTimeMillis();
        long eightDaysAgo = now - Duration.ofDays(8).toMillis();
        assertThat(WeakPasswordPolicy.isExpired(eightDaysAgo, now)).isTrue();
    }

    @Test
    void clockSkewIsTreatedAsNotExpired() {
        long now = System.currentTimeMillis();
        assertThat(WeakPasswordPolicy.isExpired(now + 60_000, now)).isFalse();
    }

    @Test
    void boundaryJustOverGraceIsExpired() {
        long now = System.currentTimeMillis();
        long justOver = now - WeakPasswordPolicy.GRACE.toMillis() - 1;
        assertThat(WeakPasswordPolicy.isExpired(justOver, now)).isTrue();
    }
}
