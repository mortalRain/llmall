package com.llmall.pay.service.impl;

import com.llmall.api.client.BalancePaymentClient;
import com.llmall.api.client.TradeClient;
import com.llmall.api.client.VoucherClient;
import com.llmall.pay.support.CouponPaymentEligibility;
import com.llmall.api.dto.BalancePaymentCommand;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.utils.UserContext;
import com.llmall.pay.domain.po.PaymentAttempt;
import com.llmall.pay.mapper.PaymentAttemptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalancePaymentCoordinator {
    private final PaymentAttemptStore store;
    private final PaymentAttemptMapper attempts;
    private final BalancePaymentClient balance;
    private final TradeClient trade;
    private final VoucherClient vouchers;
    private final PaymentSuccessPublisher publisher;
    @Value("${llmall.pay.fault-injection.after-deduct:false}")
    private boolean faultAfterDeduct;

    public void pay(Long payOrderId, String password) {
        PaymentAttempt attempt = store.claim(payOrderId, UserContext.getUser());
        try {
            TradeOrderDTO order = trade.queryOrderById(attempt.getBizOrderNo());
            if (order == null || !Objects.equals(order.getId(), attempt.getBizOrderNo())
                    || !Integer.valueOf(1).equals(order.getStatus())
                    || !Objects.equals(order.getUserId(), attempt.getUserId())
                    || !Objects.equals(order.getTotalFee(), attempt.getAmount())) {
                throw new BizIllegalException("订单状态、归属或金额已变更，无法支付");
            }
            CouponPaymentEligibility.requireBound(order, vouchers);
            balance.debit(command(attempt).setPassword(password));
            if (faultAfterDeduct) throw new BizIllegalException("支付故障注入：扣款后中断");
            store.complete(attempt);
        } catch (RuntimeException failure) {
            try { cancel(attempt); }
            catch (RuntimeException recoveryFailure) {
                log.error("支付恢复暂未完成，持久化尝试将重试: attemptId={}", attempt.getId(), recoveryFailure);
            }
            // A commit acknowledgement may be lost. Never refund a durably successful attempt.
            if (!store.succeeded(attempt.getId())) throw new BizIllegalException("支付未完成，请稍后查看订单或重试", failure);
        }
        publisher.publish(attempt);
    }

    private void cancel(PaymentAttempt attempt) {
        if (!store.beginCancel(attempt)) return;
        balance.cancel(command(attempt));
        store.finishCancel(attempt);
    }

    @Scheduled(fixedDelayString="${llmall.pay.recovery-interval-ms:30000}")
    public void recover() {
        try {
            for (PaymentAttempt attempt : attempts.recoverable()) {
                try {
                    attempts.defer(attempt.getId());
                    if (PaymentAttempt.SUCCEEDED.equals(attempt.getState())) publisher.publish(attempt);
                    else cancel(attempt);
                } catch (RuntimeException e) {
                    log.error("支付恢复失败，保留任务: attemptId={}", attempt.getId(), e);
                }
            }
        } catch (RuntimeException e) { log.error("支付恢复扫描失败", e); }
    }

    private BalancePaymentCommand command(PaymentAttempt attempt) {
        return new BalancePaymentCommand().setKey(attempt.debitKey()).setUserId(attempt.getUserId()).setAmount(attempt.getAmount());
    }
}
