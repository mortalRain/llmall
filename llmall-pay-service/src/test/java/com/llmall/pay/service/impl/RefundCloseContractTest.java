package com.llmall.pay.service.impl;

import com.llmall.pay.mapper.PayOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundCloseContractTest {
    @Test void missingPaymentMustFailSoRefundRemainsRecoverable() {
        PayOrderServiceImpl service = new PayOrderServiceImpl(null,null,null);
        ReflectionTestUtils.setField(service,"baseMapper",mock(PayOrderMapper.class));
        assertThrows(RuntimeException.class,()->service.closePayOrder(100L));
    }
}
