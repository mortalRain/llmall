package com.llmall.user.support;

import com.llmall.common.constant.AuthConstants;
import com.llmall.user.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import static com.llmall.user.support.PasswordAttemptGuard.Purpose.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordAttemptGuardTest {
    StringRedisTemplate redis;
    PasswordAttemptGuard guard;
    UserMapper users;
    PasswordEncoder encoder;
    PayPasswordVerifier verifier;

    @BeforeEach void setup() {
        redis = mock(StringRedisTemplate.class);
        guard = new PasswordAttemptGuard(redis);
        users = mock(UserMapper.class); encoder = mock(PasswordEncoder.class);
        verifier = new PayPasswordVerifier(guard, users, encoder);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(0L);
        when(users.selectPayPassword(1L)).thenReturn("hash");
        when(encoder.matches("correct", "hash")).thenReturn(true);
    }
    @Test void lockedBeforeHashCheckDoesNotQueryCredentials() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(300L);
        assertThrows(RuntimeException.class, () -> verifier.verifyForPayment(1L, "correct"));
        verifyNoInteractions(users, encoder);
    }
    @Test void successCannotClearLockCreatedDuringHashComparison() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(0L, 300L);
        assertThrows(RuntimeException.class, () -> verifier.verifyForPayment(1L, "correct"));
    }
    @Test void redisFailureDoesNotAllowHashCheck() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("outage"));
        assertThrows(RuntimeException.class, () -> verifier.verifyForPayment(1L, "correct"));
        verifyNoInteractions(users, encoder);
    }
    @Test void nullOrInvalidScriptResultsDenyVerificationAndStatus() {
        for (Long result : new Long[]{null, -1L, Long.MAX_VALUE}) {
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(result);
            assertThrows(RuntimeException.class, () -> verifier.verifyForPayment(1L, "correct"));
            assertThrows(RuntimeException.class, () -> guard.paymentStatus(1L));
        }
    }
    @Test void nullAndWrongPasswordsRecordFailure() {
        for (String password : new String[]{null, "", "wrong"}) {
            reset(redis);
            when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(0L);
            assertThrows(RuntimeException.class, () -> verifier.verifyForPayment(1L, password));
            verify(redis).execute(any(RedisScript.class), anyList(), eq("FAIL"), eq("5"), eq("300"), eq("360"));
        }
    }
    @Test void correctPasswordClearsOnlyViaAtomicSuccess() {
        verifier.verifyForPayment(1L, "correct");
        verify(redis).execute(any(RedisScript.class), anyList(), eq("SUCCESS"), eq("5"), eq("300"), eq("360"));
        verify(redis, never()).delete(anyString());
    }
    @Test void defaultPasswordCanBeChangedButCannotPay() {
        when(encoder.matches("123456", "hash")).thenReturn(true);
        assertEquals("hash", verifier.verifyForChange(1L, "123456"));
        assertThrows(RuntimeException.class, () -> verifier.verifyForPayment(1L, "123456"));
    }
    @Test void loginChangeUsesDifferentKeysFromPayment() {
        guard.success(1L, LOGIN_CHANGE);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), anyString(), anyString(), anyString(), anyString());
        assertEquals(List.of(AuthConstants.REDIS_PWD_FAIL_COUNT + "login-change:1",
                AuthConstants.REDIS_PWD_LOCK + "login-change:1"), keys.getValue());
    }
    @Test void lockedStatusRoundsUpAndRedisFailureIsNotReportedAsUnlocked() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);
        assertTrue(guard.paymentStatus(1L).getLocked());
        assertEquals(1L, guard.paymentStatus(1L).getRemainMinutes());
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("outage"));
        assertThrows(RuntimeException.class, () -> guard.paymentStatus(1L));
    }
}
