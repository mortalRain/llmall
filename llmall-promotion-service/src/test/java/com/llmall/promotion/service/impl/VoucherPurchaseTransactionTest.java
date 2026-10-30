package com.llmall.promotion.service.impl;

import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.utils.UserContext;
import com.llmall.promotion.domain.po.VoucherPurchase;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

class VoucherPurchaseTransactionTest extends VoucherPurchaseFixture {
    @Test void sameRequestReturnsTheSameCouponWithoutAnotherDebit() {
        long first = receive(REQUEST);
        assertEquals(first, receive(REQUEST));
        assertEquals(9000, funds.available); assertEquals(1, funds.debits);
        assertEquals(1, coupons()); assertEquals(1, purchases()); assertEquals("ISSUED", state());
        db.update("UPDATE voucher SET status=2,pay_value=5000 WHERE id=9");
        assertEquals(first, receive(REQUEST), "replay returns a committed fact, not current pricing");
    }
    @Test void activeReplayNeverStartsAnotherRemoteDebit() {
        store.claim(1L, 9L, REQUEST);
        assertThrows(RuntimeException.class, () -> receive(REQUEST));
        assertEquals(0, funds.debits); assertEquals(1, purchases()); assertEquals("ACTIVE", state());
    }
    @Test void lostDebitAndUnavailableCancelRemainDurableAcrossCoordinatorRestart() {
        funds.loseDebitResponse = true; funds.cancelUnavailable = true;
        assertThrows(RuntimeException.class, () -> receive(REQUEST));
        assertEquals("CANCELING", state()); assertEquals(9000, funds.available); assertEquals(0, coupons());
        funds.cancelUnavailable = false; due(); UserContext.removeUser();
        new VoucherPurchaseCoordinator(store, mapper, balance, mock(BizMetrics.class)).recover();
        assertEquals(10000, funds.available); assertEquals("CANCELED", state()); assertNull(UserContext.getUser());
    }
    @Test void repeatedFailuresNeverIncreaseBalanceAndNewRequestPaysAgain() {
        db.execute("ALTER TABLE user_voucher ADD CONSTRAINT no_issue CHECK(voucher_id<>9)");
        for (int i=0;i<3;i++) {
            assertThrows(RuntimeException.class, () -> receive(REQUEST + purchases()));
            assertEquals(10000, funds.available);
        }
        assertEquals(3, funds.debits); assertEquals(0, coupons());
        db.execute("ALTER TABLE user_voucher DROP CONSTRAINT no_issue");
        receive("new-request-after-refund");
        assertEquals(9000, funds.available); assertEquals(4, funds.debits); assertEquals(1, coupons());
    }
    @Test void canceledRequestCannotSilentlyBecomeANewPurchase() {
        funds.loseDebitResponse = true;
        assertThrows(RuntimeException.class, () -> receive(REQUEST));
        funds.loseDebitResponse = false;
        assertThrows(RuntimeException.class, () -> receive(REQUEST));
        assertEquals(1, funds.debits); assertEquals(0, coupons()); assertEquals(10000, funds.available);
    }
    @Test void issueRollbackCannotLeaveACouponWithoutItsSuccessfulPurchase() {
        VoucherPurchase p = store.claim(1L, 9L, REQUEST).getPurchase();
        db.execute("ALTER TABLE voucher_purchase ADD CONSTRAINT no_success CHECK(state<>'ISSUED')");
        assertThrows(RuntimeException.class, () -> store.issue(p.getId()));
        assertEquals(0, coupons()); assertEquals("ACTIVE", state());
    }
    @Test void lostIssueCommitAcknowledgmentNeverRefundsAnIssuedCoupon() {
        VoucherPurchaseStore lost = mock(VoucherPurchaseStore.class, delegatesTo(store));
        doAnswer(c -> { store.issue(c.getArgument(0)); throw new IllegalStateException("commit response lost"); }).when(lost).issue(anyLong());
        var c = new VoucherPurchaseCoordinator(lost, mapper, balance, mock(BizMetrics.class));
        assertTrue(c.receive(9L, "password", REQUEST)>0);
        assertEquals("ISSUED", state()); assertEquals(1, coupons()); assertEquals(0, funds.cancels); assertEquals(9000, funds.available);
    }
    @Test void expiredActiveClaimIsCanceledWithoutReplayingAnyPassword() {
        store.claim(1L, 9L, REQUEST); due(); UserContext.removeUser();
        coordinator.recover();
        assertEquals("CANCELED", state()); assertEquals(0, funds.debits); assertEquals(10000, funds.available);
    }
    @Test void lostCancellationResponseIsSafelyRetried() {
        funds.loseDebitResponse = true; funds.loseCancelResponse = true;
        assertThrows(RuntimeException.class, () -> receive(REQUEST));
        assertEquals("CANCELING", state()); assertEquals(10000, funds.available);
        funds.loseCancelResponse = false; due(); coordinator.recover();
        assertEquals("CANCELED", state()); assertEquals(10000, funds.available);
    }
    @Test void concurrentDifferentRequestsHaveOnlyOneActiveClaim() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i=0;i<6;i++) { final int n=i; results.add(pool.submit(() -> {
                start.await(); try { store.claim(1L, 9L, REQUEST+n); return true; } catch (RuntimeException expected) { return false; }
            })); }
            start.countDown(); int accepted=0; for (var r:results) if(r.get(15, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted); assertEquals(1, purchases());
        } finally { pool.shutdownNow(); }
    }
    @Test void lateDebitAfterRecoveryCancellationCannotIssueOrConsumeMoney() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1), proceed = new CountDownLatch(1);
        funds.beforeDebit = () -> { waiting.countDown(); try { if(!proceed.await(15, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
            catch(InterruptedException e){ Thread.currentThread().interrupt(); throw new RuntimeException(e); } };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = pool.submit(() -> {UserContext.setUser(1L);try { assertThrows(RuntimeException.class, () -> receive(REQUEST)); } finally {UserContext.removeUser();}});
            assertTrue(waiting.await(15, TimeUnit.SECONDS)); due(); UserContext.removeUser(); coordinator.recover();
            proceed.countDown(); request.get(15, TimeUnit.SECONDS);
            assertEquals("CANCELED", state()); assertEquals(10000, funds.available); assertEquals(0, coupons());
        } finally {proceed.countDown();pool.shutdownNow();}
    }
    @Test void snapshotsDoNotRepriceAnAcceptedPurchase() {
        VoucherPurchase p = store.claim(1L, 9L, REQUEST).getPurchase();
        db.update("UPDATE voucher SET shop_id=8,pay_value=9000,rules='{\"validDays\":90}' WHERE id=9");
        long coupon = store.issue(p.getId());
        assertEquals(1000, mapper.selectById(p.getId()).getAmount());
        assertEquals(3L, db.queryForObject("SELECT shop_id FROM user_voucher WHERE id=?", Long.class, coupon));
        assertEquals(p.getExpireTime(), db.queryForObject("SELECT expire_time FROM user_voucher WHERE id=?", java.sql.Timestamp.class, coupon).toLocalDateTime());
    }
    @Test void ownershipAndRequestParametersAreBound() {
        store.claim(1L, 9L, REQUEST);
        db.update("INSERT INTO voucher(id,pay_value,status) VALUES(10,1000,1)");
        assertThrows(RuntimeException.class, () -> store.claim(1L, 10L, REQUEST));
        UserContext.setUser(2L);
        assertEquals("NOT_FOUND", coordinator.status(REQUEST).getState());
        UserContext.removeUser(); assertThrows(RuntimeException.class, () -> coordinator.status(REQUEST));
        assertEquals(1, purchases());
    }
    @Test void failedFirstRecoveryBatchDoesNotStarveLaterRecords() {
        for (long id=1;id<=102;id++) db.update("INSERT INTO voucher_purchase(id,user_id,voucher_id,request_id,amount,state,create_time,next_check_time) VALUES(?,?,9,?,1000,'ACTIVE',TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2020-01-01 00:00:00')",
                id, id, "batch-request-number-"+id);
        funds.cancelUnavailable=true;
        coordinator.recover();
        assertEquals(100, db.queryForObject("SELECT COUNT(*) FROM voucher_purchase WHERE state='CANCELING'", Integer.class));
        coordinator.recover();
        assertEquals(102, db.queryForObject("SELECT COUNT(*) FROM voucher_purchase WHERE state='CANCELING'", Integer.class));
    }
}
