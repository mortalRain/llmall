package com.llmall.pay.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.pay.domain.po.PayOrder;
import com.llmall.pay.domain.po.PaymentAttempt;
import com.llmall.pay.mapper.PayOrderMapper;
import com.llmall.pay.mapper.PaymentAttemptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;

/** All local state transitions take the payment row lock first. No remote calls in these transactions. */
@Service
@RequiredArgsConstructor
public class PaymentAttemptStore {
    private final PayOrderMapper orders;
    private final PaymentAttemptMapper attempts;

    @Transactional(rollbackFor = Exception.class)
    public PaymentAttempt claim(Long payOrderId, Long userId) {
        PayOrder order = orders.lockById(payOrderId);
        if (order == null) throw new BizIllegalException("支付单不存在");
        if (userId == null || !Objects.equals(userId, order.getBizUserId())) {
            throw new ForbiddenException("无权支付他人订单");
        }
        if (!Integer.valueOf(1).equals(order.getStatus())) throw new BizIllegalException("支付处理中、已支付或已关闭");
        if ("UNCERTAIN".equals(order.getResultCode())) throw new BizIllegalException("历史支付待核对，暂不能重试");
        if (order.getAmount() == null || order.getAmount() <= 0) throw new BizIllegalException("支付金额非法");
        PaymentAttempt attempt = new PaymentAttempt().setId(IdWorker.getId()).setPayOrderId(payOrderId)
                .setBizOrderNo(order.getBizOrderNo()).setUserId(userId).setAmount(order.getAmount())
                .setState(PaymentAttempt.ACTIVE).setNotified(false).setCreateTime(LocalDateTime.now())
                .setNextCheckTime(LocalDateTime.now().plusMinutes(2));
        attempts.insert(attempt);
        orders.updateById(new PayOrder().setId(payOrderId).setStatus(4).setResultCode("PROCESSING")
                .setResultMsg("attempt:" + attempt.getId()));
        return attempt;
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(PaymentAttempt attempt) {
        PayOrder order = orders.lockById(attempt.getPayOrderId());
        PaymentAttempt current = attempts.selectById(attempt.getId());
        if (current != null && PaymentAttempt.SUCCEEDED.equals(current.getState())) return;
        if (current == null || !PaymentAttempt.ACTIVE.equals(current.getState())
                || order == null || !Integer.valueOf(4).equals(order.getStatus())) {
            throw new BizIllegalException("支付尝试已进入撤销流程");
        }
        attempts.updateById(new PaymentAttempt().setId(attempt.getId()).setState(PaymentAttempt.SUCCEEDED)
                .setNextCheckTime(LocalDateTime.now()));
        orders.updateById(new PayOrder().setId(order.getId()).setStatus(3).setResultCode("SUCCESS")
                .setPaySuccessTime(LocalDateTime.now()));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean beginCancel(PaymentAttempt attempt) {
        orders.lockById(attempt.getPayOrderId());
        PaymentAttempt current = attempts.selectById(attempt.getId());
        if (current == null) throw new BizIllegalException("支付尝试不存在");
        if (PaymentAttempt.SUCCEEDED.equals(current.getState()) || PaymentAttempt.CANCELED.equals(current.getState())) return false;
        attempts.updateById(new PaymentAttempt().setId(attempt.getId()).setState(PaymentAttempt.CANCELING)
                .setNextCheckTime(LocalDateTime.now().plusSeconds(30)));
        orders.updateById(new PayOrder().setId(attempt.getPayOrderId()).setResultCode("UNCERTAIN")
                .setResultMsg("attempt:" + attempt.getId()));
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public void finishCancel(PaymentAttempt attempt) {
        PayOrder order = orders.lockById(attempt.getPayOrderId());
        PaymentAttempt current = attempts.selectById(attempt.getId());
        if (current == null || !PaymentAttempt.CANCELING.equals(current.getState())) return;
        if (order == null || !Integer.valueOf(4).equals(order.getStatus())) throw new BizIllegalException("支付状态异常");
        attempts.updateById(new PaymentAttempt().setId(attempt.getId()).setState(PaymentAttempt.CANCELED));
        orders.updateById(new PayOrder().setId(order.getId()).setStatus(1).setResultCode("CANCELED_ATTEMPT")
                .setResultMsg("attempt:" + attempt.getId()));
    }

    public boolean succeeded(Long id) {
        PaymentAttempt attempt = attempts.selectById(id);
        return attempt != null && PaymentAttempt.SUCCEEDED.equals(attempt.getState());
    }
}
