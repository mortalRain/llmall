package com.llmall.user.support;

import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.BizIllegalException;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 验证码的策略与 key 口径：有效期、发送频控、尝试次数、场景隔离。
 *
 * 【为什么单独抽出来】这些参数会被 service、Lua 脚本参数与单元测试同时引用；
 * 散落在 service 里时，改一个值很难确认有没有别处跟着漂移。
 */
public final class VerificationCodePolicy {

    /** 验证码有效期 */
    public static final Duration CODE_TTL = Duration.ofMinutes(5);

    /** 同一手机号两次发送的最小间隔 */
    public static final Duration SEND_INTERVAL = Duration.ofSeconds(60);

    /** 同一手机号发送计数的统计窗口 */
    public static final Duration SEND_WINDOW = Duration.ofHours(1);

    /** 同一手机号在统计窗口内最多发送几次（防止换场景刷短信） */
    public static final int SEND_MAX_PER_WINDOW = 10;

    /** 单个验证码允许的最大错误次数，达到即作废并锁定 */
    public static final int MAX_ATTEMPTS = 5;

    /** 错误计数窗口，与验证码有效期解耦：验证码过期后计数仍保留一小段时间 */
    public static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(10);

    /** 达到错误上限后的锁定时间 */
    public static final Duration LOCK = Duration.ofMinutes(10);

    /** 登录场景 */
    public static final String SCENE_LOGIN = "login";

    /** 修改支付口令场景 */
    public static final String SCENE_PAY_PASSWORD = "change-pay-password";

    /** 修改登录口令场景 */
    public static final String SCENE_LOGIN_PASSWORD = "change-login-password";

    private static final Set<String> SCENES = new HashSet<>(Arrays.asList(
            SCENE_LOGIN, SCENE_PAY_PASSWORD, SCENE_LOGIN_PASSWORD));

    private VerificationCodePolicy() {
    }

    /**
     * 归一化并校验场景。
     *
     * 【注意】不认识的场景必须拒绝而不是回落到登录场景：后者会让"给改密接口发一个登录码"
     * 这类误用静默通过，场景隔离形同虚设。
     */
    public static String normalizeScene(String scene) {
        if (scene == null || scene.trim().isEmpty()) {
            return SCENE_LOGIN;
        }
        String normalized = scene.trim().toLowerCase();
        if (!SCENES.contains(normalized)) {
            throw new BizIllegalException("不支持的验证码场景: " + scene);
        }
        return normalized;
    }

    /** 验证码 key：auth:code:{scene}:{phone} */
    public static String codeKey(String scene, String phone) {
        return AuthConstants.REDIS_LOGIN_CODE + scene + ":" + phone;
    }

    /** 错误计数 key */
    public static String failKey(String scene, String phone) {
        return AuthConstants.REDIS_CODE_FAIL + scene + ":" + phone;
    }

    /** 锁定 key */
    public static String lockKey(String scene, String phone) {
        return AuthConstants.REDIS_CODE_LOCK + scene + ":" + phone;
    }

    /** 发送窗口计数 key */
    public static String sendWindowKey(String phone) {
        return AuthConstants.REDIS_CODE_LIMIT + "window:" + phone;
    }

    /** 是否已达到错误次数上限 */
    public static boolean attemptsExhausted(long attempts) {
        return attempts >= MAX_ATTEMPTS;
    }
}
