package com.llmall.user.support;

import com.llmall.common.exception.BizIllegalException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证码策略的单元测试：场景隔离、key 形态与错误次数边界。
 */
class VerificationCodePolicyTest {

    @Test
    void blankSceneFallsBackToLogin() {
        assertThat(VerificationCodePolicy.normalizeScene(null)).isEqualTo(VerificationCodePolicy.SCENE_LOGIN);
        assertThat(VerificationCodePolicy.normalizeScene("  ")).isEqualTo(VerificationCodePolicy.SCENE_LOGIN);
    }

    @Test
    void sceneIsTrimmedAndLowercased() {
        assertThat(VerificationCodePolicy.normalizeScene(" Change-Pay-Password "))
                .isEqualTo(VerificationCodePolicy.SCENE_PAY_PASSWORD);
    }

    @Test
    void unknownSceneIsRejected() {
        assertThatThrownBy(() -> VerificationCodePolicy.normalizeScene("admin"))
                .isInstanceOf(BizIllegalException.class);
    }

    @Test
    void keysAreSceneScoped() {
        String loginKey = VerificationCodePolicy.codeKey(VerificationCodePolicy.SCENE_LOGIN, "13800000000");
        String changeKey = VerificationCodePolicy.codeKey(
                VerificationCodePolicy.SCENE_PAY_PASSWORD, "13800000000");
        assertThat(loginKey).isNotEqualTo(changeKey);
        assertThat(loginKey).contains("login").contains("13800000000");
        assertThat(changeKey).contains("change-pay-password");
    }

    @Test
    void codeFailAndLockKeysDifferFromCodeKey() {
        String phone = "13800000000";
        String scene = VerificationCodePolicy.SCENE_LOGIN;
        assertThat(VerificationCodePolicy.failKey(scene, phone))
                .isNotEqualTo(VerificationCodePolicy.codeKey(scene, phone));
        assertThat(VerificationCodePolicy.lockKey(scene, phone))
                .isNotEqualTo(VerificationCodePolicy.failKey(scene, phone));
    }

    @Test
    void maxAttemptsBoundary() {
        assertThat(VerificationCodePolicy.attemptsExhausted(0)).isFalse();
        assertThat(VerificationCodePolicy.attemptsExhausted(4)).isFalse();
        assertThat(VerificationCodePolicy.attemptsExhausted(5)).isTrue();
        assertThat(VerificationCodePolicy.attemptsExhausted(6)).isTrue();
    }

    @Test
    void lockIsLongerThanSendInterval() {
        assertThat(VerificationCodePolicy.LOCK).isGreaterThan(VerificationCodePolicy.SEND_INTERVAL);
        assertThat(VerificationCodePolicy.MAX_ATTEMPTS).isPositive();
    }
}
