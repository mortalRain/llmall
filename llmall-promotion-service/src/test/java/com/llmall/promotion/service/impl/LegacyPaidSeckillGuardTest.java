package com.llmall.promotion.service.impl;

import com.llmall.api.client.UserClient;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.utils.UserContext;
import com.llmall.promotion.domain.po.Voucher;
import com.llmall.promotion.mapper.SeckillVoucherMapper;
import com.llmall.promotion.mapper.VoucherMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyPaidSeckillGuardTest {
    private final VoucherMapper mapper = mock(VoucherMapper.class);
    private final SeckillVoucherMapper config = mock(SeckillVoucherMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UserClient users = mock(UserClient.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private VoucherServiceImpl service;

    @BeforeEach
    void setup() {
        service = new VoucherServiceImpl(null, redis, rabbit, config, null, null, users, null, null, null, null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserContext.setUser(1L);
    }

    @AfterEach
    void cleanup() { UserContext.removeUser(); }

    @ParameterizedTest
    @ValueSource(longs = {1, 100, Integer.MAX_VALUE, -1, 2147483648L})
    void paidOrInvalidPricesStopBeforeConfigRedisAndMoney(long price) {
        when(mapper.selectById(8L)).thenReturn(voucher(price));
        when(config.selectById(8L)).thenThrow(new AssertionError("crossed eligibility boundary"));
        assertThrows(BadRequestException.class, () -> service.seckill(8L, "654321"));
        verifyNoInteractions(config, redis, users, rabbit);
    }

    @ParameterizedTest
    @ValueSource(longs = {0})
    @NullSource
    void freeRequestsStillReachLegacyConfigValidation(Long price) {
        when(mapper.selectById(8L)).thenReturn(voucher(price));
        assertThrows(BizIllegalException.class, () -> service.seckill(8L, null));
        verify(config).selectById(8L);
        verifyNoInteractions(redis, users, rabbit);
    }

    private Voucher voucher(Long price) {
        Voucher voucher = new Voucher();
        voucher.setId(8L); voucher.setStatus(Voucher.STATUS_ON); voucher.setPayValue(price);
        return voucher;
    }
}
