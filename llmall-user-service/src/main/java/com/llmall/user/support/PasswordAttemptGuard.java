package com.llmall.user.support;

import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.user.domain.vo.PwdLockStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.Arrays;

/** Account-scoped error budget. Redis failures deny verification; no password enters Redis. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordAttemptGuard {
    public enum Purpose { PAYMENT, LOGIN_CHANGE }
    private static final String MAX_FAILURES = "5";
    private static final String LOCK_SECONDS = "300";
    private static final String FAILURE_SECONDS = "360";
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("lua/password-attempt.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;

    public void check(Long userId, Purpose purpose) { assertUnlocked(run(userId, purpose, "CHECK")); }
    public void failure(Long userId, Purpose purpose) { assertUnlocked(run(userId, purpose, "FAIL")); }
    public void success(Long userId, Purpose purpose) { assertUnlocked(run(userId, purpose, "SUCCESS")); }

    public PwdLockStatusVO paymentStatus(Long userId) {
        long seconds = run(userId, Purpose.PAYMENT, "CHECK");
        return new PwdLockStatusVO().setLocked(seconds > 0).setRemainMinutes((seconds + 59) / 60);
    }

    private long run(Long userId, Purpose purpose, String action) {
        if (userId == null || userId <= 0) throw new UnauthorizedException("未登录");
        if (purpose == null) throw new IllegalArgumentException("Missing password purpose");
        // Preserve existing payment locks; login changes cannot reset payment's error budget.
        String suffix = purpose == Purpose.PAYMENT ? userId.toString() : "login-change:" + userId;
        try {
            Long result = redis.execute(SCRIPT, Arrays.asList(AuthConstants.REDIS_PWD_FAIL_COUNT + suffix,
                    AuthConstants.REDIS_PWD_LOCK + suffix), action, MAX_FAILURES, LOCK_SECONDS, FAILURE_SECONDS);
            if (result == null || result < 0 || result > Integer.MAX_VALUE) {
                throw new IllegalStateException("Invalid password guard result");
            }
            return result;
        } catch (Exception e) {
            log.warn("口令安全状态不可用，拒绝校验: userId={}, purpose={}, failure={}", userId, purpose, e.getClass().getSimpleName());
            throw new BizIllegalException("口令安全校验暂不可用，请稍后重试");
        }
    }

    private void assertUnlocked(long seconds) {
        if (seconds > 0) {
            throw new BizIllegalException("口令连续输错次数过多，已临时锁定，请 " + ((seconds + 59) / 60) + " 分钟后再试");
        }
    }
}
