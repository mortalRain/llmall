package com.llmall.pay.service.impl;

import com.llmall.api.client.BalancePaymentClient;
import com.llmall.api.client.TradeClient;
import com.llmall.api.client.VoucherClient;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.api.dto.UserVoucherDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.utils.UserContext;
import com.llmall.pay.domain.dto.PayApplyDTO;
import com.llmall.pay.domain.po.PayOrder;
import com.llmall.pay.domain.po.PaymentAttempt;
import com.llmall.pay.mapper.PayOrderMapper;
import com.llmall.pay.mapper.PaymentAttemptMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponPaymentEligibilityTest {
    @Mock TradeClient trade;
    @Mock VoucherClient vouchers;
    @Mock BalancePaymentClient balance;
    @Mock PaymentAttemptStore store;
    @Mock PaymentAttemptMapper attempts;
    @Mock PaymentSuccessPublisher publisher;
    @Mock PayOrderMapper mapper;
    @InjectMocks BalancePaymentCoordinator coordinator;
    @InjectMocks PayOrderServiceImpl service;

    private TradeOrderDTO order;
    private UserVoucherDTO voucher;
    private PaymentAttempt attempt;

    @BeforeEach
    void setup() {
        UserContext.setUser(1L);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        order = new TradeOrderDTO();
        order.setId(100L); order.setUserId(1L); order.setTotalFee(300); order.setStatus(1);
        order.setShopId(10L); order.setVoucherId(8L);
        voucher = new UserVoucherDTO();
        voucher.setId(8L); voucher.setStatus(2); voucher.setOrderId(100L); voucher.setShopId(10L);
        attempt = new PaymentAttempt().setId(9L).setPayOrderId(20L).setBizOrderNo(100L)
                .setUserId(1L).setAmount(300).setState(PaymentAttempt.ACTIVE);
        when(trade.queryOrderById(100L)).thenReturn(order);
        when(vouchers.queryMyVoucher(8L)).thenReturn(voucher);
        when(store.claim(20L, 1L)).thenReturn(attempt);
    }

    @AfterEach
    void cleanup() { UserContext.removeUser(); }

    enum Invalid { UNBOUND, OTHER_ORDER, MISSING_ORDER, WRONG_ID, WRONG_SHOP, MISSING, UNAVAILABLE }

    private void invalidate(Invalid invalid) {
        switch (invalid) {
            case UNBOUND: voucher.setStatus(1); break;
            case OTHER_ORDER: voucher.setOrderId(200L); break;
            case MISSING_ORDER: voucher.setOrderId(null); break;
            case WRONG_ID: voucher.setId(99L); break;
            case WRONG_SHOP: voucher.setShopId(11L); break;
            case MISSING: when(vouchers.queryMyVoucher(8L)).thenReturn(null); break;
            case UNAVAILABLE: when(vouchers.queryMyVoucher(8L)).thenThrow(new IllegalStateException("offline")); break;
        }
    }

    @ParameterizedTest
    @EnumSource(Invalid.class)
    void applicationRejectsUnconfirmedCouponBeforePersistence(Invalid invalid) {
        invalidate(invalid);
        assertThrows(BizIllegalException.class, () -> service.applyPayOrder(form()));
        verifyNoInteractions(mapper, balance, publisher);
    }

    @ParameterizedTest
    @EnumSource(Invalid.class)
    void existingPayOrderCannotBypassCouponCheck(Invalid invalid) {
        invalidate(invalid);
        when(store.beginCancel(attempt)).thenReturn(true);
        assertThrows(BizIllegalException.class, () -> coordinator.pay(20L, "654321"));
        verify(balance, never()).debit(any());
        verify(balance).cancel(any());
        verify(store).finishCancel(attempt);
        verify(store, never()).complete(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void boundCouponAllowsApplicationAndDebit() {
        assertNotNull(service.applyPayOrder(form()));
        coordinator.pay(20L, "654321");
        verify(mapper).saveOrGetExisting(any(PayOrder.class));
        verify(vouchers, times(2)).queryMyVoucher(8L);
        verify(balance).debit(argThat(command -> command.getAmount() == 300 && command.getUserId() == 1L));
        verify(store).complete(attempt);
        verify(publisher).publish(attempt);
    }

    @Test
    void platformCouponIsAllowed() {
        voucher.setShopId(null);
        coordinator.pay(20L, "654321");
        verify(balance).debit(any());
    }

    @Test
    void noCouponAddsNoMarketingDependency() {
        order.setVoucherId(null);
        assertNotNull(service.applyPayOrder(form()));
        coordinator.pay(20L, "654321");
        verifyNoInteractions(vouchers);
        verify(balance).debit(any());
    }

    @Test
    void applicationRejectsAnUnrelatedOrderEvenWithMatchingOwnerAndAmount() {
        order.setId(200L);
        voucher.setOrderId(200L);
        assertThrows(BizIllegalException.class, () -> service.applyPayOrder(form()));
        verifyNoInteractions(mapper, vouchers, balance, publisher);
    }

    @Test
    void debitRejectsAnUnrelatedOrderEvenWithMatchingOwnerAndAmount() {
        order.setId(200L);
        voucher.setOrderId(200L);
        assertThrows(BizIllegalException.class, () -> coordinator.pay(20L, "654321"));
        verifyNoInteractions(vouchers);
        verify(balance, never()).debit(any());
        verifyNoInteractions(publisher);
    }

    private PayApplyDTO form() {
        PayApplyDTO form = PayApplyDTO.builder().payType(3).orderInfo("test").build();
        form.setBizOrderNo(100L); form.setAmount(300); form.setPayChannelCode("balance");
        return form;
    }
}
