package com.llmall.user.service.impl;

import com.llmall.common.utils.UserContext;
import com.llmall.user.domain.po.User;
import com.llmall.user.mapper.*;
import com.llmall.user.service.ITokenService;
import com.llmall.user.sms.CodeSender;
import com.llmall.user.support.PasswordAttemptGuard;
import com.llmall.user.support.PayPasswordVerifier;
import com.llmall.common.metrics.BizMetrics;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserDebitSecurityTest {
    @Mock PasswordEncoder passwordEncoder;
    @Mock ITokenService tokenService;
    @Mock StringRedisTemplate redisTemplate;
    @Mock UserInfoMapper userInfoMapper;
    @Mock BalanceFlowMapper balanceFlowMapper;
    @Mock CodeSender codeSender;
    @Mock BizMetrics metrics;
    @Mock UserMapper users;
    @InjectMocks UserServiceImpl service;
    AutoCloseable mocks;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(service, "baseMapper", users);
        PasswordAttemptGuard guard = new PasswordAttemptGuard(redisTemplate);
        ReflectionTestUtils.setField(service, "passwordAttempts", guard);
        ReflectionTestUtils.setField(service, "payPasswords", new PayPasswordVerifier(guard, users, passwordEncoder));
        when(users.selectById(1L)).thenReturn(new User().setId(1L).setBalance(1000));
        when(users.selectPayPassword(1L)).thenReturn("hash");
        when(passwordEncoder.matches("654321", "hash")).thenReturn(true);
        when(users.updateMoney(1L, 100)).thenReturn(1);
        UserContext.setUser(1L);
    }
    @AfterEach void cleanup() throws Exception { UserContext.removeUser(); mocks.close(); }

    @Test void lockedAccountCannotDebitEvenWithCorrectPassword() {
        when(redisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenReturn(300L);
        assertThrows(RuntimeException.class, () -> service.deductMoney("654321", 100, "security-test"));
        verify(users, never()).updateMoney(anyLong(), anyInt());
        verifyNoInteractions(balanceFlowMapper);
    }
    @Test void redisFailureCannotDebit() {
        when(redisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("fixture outage"));
        assertThrows(RuntimeException.class, () -> service.deductMoney("654321", 100, "security-test"));
        verify(users, never()).updateMoney(anyLong(), anyInt());
        verifyNoInteractions(balanceFlowMapper);
    }
}
