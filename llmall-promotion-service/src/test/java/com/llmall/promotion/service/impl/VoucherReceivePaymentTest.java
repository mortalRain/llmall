package com.llmall.promotion.service.impl;

import com.llmall.common.metrics.BizMetrics;
import com.llmall.promotion.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The original purchase regressions now exercise real local SQL and the durable protocol. */
class VoucherReceivePaymentTest extends VoucherPurchaseFixture {
    private VoucherServiceImpl service;
    @BeforeEach void connectEntryPoint() {
        var sql = context.getBean(org.mybatis.spring.SqlSessionTemplate.class);
        service = new VoucherServiceImpl(null, null, null, sql.getMapper(SeckillVoucherMapper.class),
                sql.getMapper(UserVoucherMapper.class), null, null, null, null, mock(BizMetrics.class), coordinator);
    }
    private long receiveEntry(String token) { return service.receive(9L, "password", token); }

    @Test void repeatedIssuanceFailuresCannotIncreaseBalance() {
        db.execute("ALTER TABLE user_voucher ADD CONSTRAINT reject_issue CHECK(voucher_id<>9)");
        for (int i=0;i<3;i++) {
            final String token=REQUEST+i;
            assertThrows(RuntimeException.class, () -> receiveEntry(token));
            assertEquals(10000, funds.available);
        }
        assertEquals(3, funds.debits); assertEquals(0, coupons());
    }
    @Test void successfulRetryAfterRefundMustDebitAgain() {
        db.execute("ALTER TABLE user_voucher ADD CONSTRAINT reject_issue CHECK(voucher_id<>9)");
        assertThrows(RuntimeException.class, () -> receiveEntry(REQUEST));
        db.execute("ALTER TABLE user_voucher DROP CONSTRAINT reject_issue");
        receiveEntry("new-request-after-refund");
        assertEquals(9000, funds.available); assertEquals(2, funds.debits); assertEquals(1, coupons());
    }
    @Test void losingUniqueConstraintRaceRefundsItsOwnDebit() {
        funds.beforeDebit = () -> db.update("INSERT INTO user_voucher(user_id,voucher_id,status) VALUES(1,9,1)");
        assertThrows(RuntimeException.class, () -> receiveEntry(REQUEST));
        assertEquals(10000, funds.available); assertEquals(1, funds.debits); assertEquals(1, coupons());
        assertEquals("CANCELED", state());
    }
    @Test void ordinaryReceiveRejectsSeckillBeforeCharging() {
        db.update("INSERT INTO seckill_voucher(voucher_id,stock) VALUES(9,10)");
        assertThrows(RuntimeException.class, () -> receiveEntry(REQUEST));
        assertEquals(0, funds.debits); assertEquals(0, coupons()); assertEquals(0, purchases());
    }
    @Test void freeVoucherDoesNotCallFundsService() {
        db.update("UPDATE voucher SET pay_value=0 WHERE id=9");
        assertTrue(service.receive(9L)>0);
        assertEquals(0, funds.debits); assertEquals(0, funds.cancels); assertEquals(1, coupons());
    }
    @Test void invalidPriceCannotOverflowOrBecomeFree() {
        for (long price : new long[]{-1, (long) Integer.MAX_VALUE+1}) {
            db.update("UPDATE voucher SET pay_value=? WHERE id=9", price);
            assertThrows(RuntimeException.class, () -> receiveEntry(REQUEST));
        }
        assertEquals(0, funds.debits); assertEquals(0, coupons()); assertEquals(0, purchases());
    }
    @Test void alreadyHeldVoucherIsRejectedBeforeDebit() {
        db.update("INSERT INTO user_voucher(user_id,voucher_id,status) VALUES(1,9,1)");
        assertThrows(RuntimeException.class, () -> receiveEntry(REQUEST));
        assertEquals(0, funds.debits); assertEquals(0, purchases());
    }
    @Test void uncertainDebitIsAtomicallyCanceledInsteadOfGuessingARefund() {
        funds.loseDebitResponse=true;
        assertThrows(RuntimeException.class, () -> receiveEntry(REQUEST));
        assertEquals(10000, funds.available); assertEquals(0, coupons()); assertEquals("CANCELED", state());
    }
    @Test void paidLegacyEntryRequiresAClientRequestId() {
        assertThrows(RuntimeException.class, () -> service.receive(9L, "password"));
        assertEquals(0, purchases()); assertEquals(0, funds.debits);
    }
}