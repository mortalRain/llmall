package com.llmall.promotion.service.impl;

import com.llmall.api.client.BalancePaymentClient;
import com.llmall.api.dto.BalancePaymentCommand;
import com.llmall.common.exception.ConflictException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.utils.UserContext;
import com.llmall.promotion.domain.po.VoucherPurchase;
import com.llmall.promotion.domain.vo.VoucherPurchaseVO;
import com.llmall.promotion.mapper.VoucherPurchaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/** Coordinates short local transactions and atomic user-domain cancellation; never stores passwords. */
@Service @Slf4j @RequiredArgsConstructor
public class VoucherPurchaseCoordinator {
    private final VoucherPurchaseStore store;
    private final VoucherPurchaseMapper purchases;
    private final BalancePaymentClient balance;
    private final BizMetrics metrics;

    public Long receive(Long voucherId, String password, String requestId) {
        Long userId = requireUser();
        VoucherPurchaseStore.Claim claim;
        try { claim = store.claim(userId, voucherId, requestId); }
        catch (DuplicateKeyException conflict) {
            // Cross-voucher reuse of one token can race on the unique key; read after rollback.
            VoucherPurchase existing = store.find(userId, requestId);
            if (existing == null) throw conflict;
            VoucherPurchaseStore.assertSameVoucher(existing, voucherId);
            return replay(existing);
        }
        VoucherPurchase p = claim.getPurchase();
        if (!claim.isCreated()) return replay(p);
        try {
            if (p.getAmount() > 0) balance.debit(command(p).setPassword(password));
            return store.issue(p.getId());
        } catch (RuntimeException | AssertionError failure) {
            try { cancel(p.getId()); }
            catch (RuntimeException | AssertionError recoveryFailure) {
                recordFailure(p.getId(), recoveryFailure);
            }
            VoucherPurchase current = store.current(p.getId());
            if (current != null && VoucherPurchase.ISSUED.equals(current.getState())) return VoucherPurchaseStore.requireCoupon(current);
            // Details/credentials from a remote exception must not be reflected into logs or API responses.
            throw new ConflictException("领券未完成，请查询原请求；确认已取消后可重新购买");
        }
    }

    public VoucherPurchaseVO status(String requestId) {
        return VoucherPurchaseVO.of(requestId, store.find(requireUser(), requestId));
    }

    @Scheduled(fixedDelayString = "${llmall.promotion.purchase-recovery-interval-ms:30000}")
    public void recover() {
        LocalDateTime now = LocalDateTime.now();
        try {
            for (VoucherPurchase p : purchases.recoverable(now, now.minusMinutes(2))) {
                try {
                    purchases.defer(p.getId(), LocalDateTime.now().plusSeconds(30));
                    cancel(p.getId());
                } catch (RuntimeException | AssertionError failure) { recordFailure(p.getId(), failure); }
            }
        } catch (RuntimeException | AssertionError failure) { recordFailure(null, failure); }
    }

    private void cancel(Long id) {
        VoucherPurchase p = store.beginCancel(id);
        if (p == null) return;
        if (p.getAmount() > 0) balance.cancel(command(p));
        store.finishCancel(id);
    }
    private Long replay(VoucherPurchase p) {
        if (VoucherPurchase.ISSUED.equals(p.getState())) return VoucherPurchaseStore.requireCoupon(p);
        if (VoucherPurchase.CANCELED.equals(p.getState())) throw new ConflictException("该购买请求已取消，请确认后发起新请求");
        throw new ConflictException("购买请求处理中，请查询原请求");
    }
    private BalancePaymentCommand command(VoucherPurchase p) {
        return new BalancePaymentCommand().setKey(p.paymentKey()).setUserId(p.getUserId()).setAmount(p.getAmount());
    }
    private void recordFailure(Long id, Throwable failure) {
        log.error("普通券购买恢复失败，保留持久化待办: purchaseId={}, failureType={}", id, failure.getClass().getSimpleName());
        metrics.compensationFailure("promotion", "voucher-purchase");
    }
    private Long requireUser() {
        Long userId = UserContext.getUser();
        if (userId == null || userId <= 0) throw new UnauthorizedException("请先登录");
        return userId;
    }
}
