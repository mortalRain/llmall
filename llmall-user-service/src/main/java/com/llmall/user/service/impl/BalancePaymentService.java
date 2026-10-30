package com.llmall.user.service.impl;

import com.llmall.api.dto.BalancePaymentCommand;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.utils.UserContext;
import com.llmall.user.domain.po.BalanceFlow;
import com.llmall.user.domain.po.BalancePayment;
import com.llmall.user.mapper.BalanceFlowMapper;
import com.llmall.user.mapper.BalancePaymentMapper;
import com.llmall.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import com.llmall.user.support.PayPasswordVerifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Debit and cancellation serialize on the same durable operation row, including cancel-before-debit. */
@Service
@RequiredArgsConstructor
public class BalancePaymentService {
    private final BalancePaymentMapper payments;
    private final BalanceFlowMapper flows;
    private final UserMapper users;
    private final PayPasswordVerifier passwords;

    @Transactional(rollbackFor = Exception.class)
    public void debit(BalancePaymentCommand command) {
        validate(command);
        if (!Objects.equals(UserContext.getUser(), command.getUserId())) {
            throw new ForbiddenException("付款人身份不匹配");
        }
        passwords.verifyForPayment(command.getUserId(), command.getPassword());
        BalancePayment payment = lock(command);
        if ("DEBITED".equals(payment.getState())) return;
        if (!"NEW".equals(payment.getState())) throw new BizIllegalException("本次支付已撤销，请重新发起支付");
        if (users.updateMoney(command.getUserId(), command.getAmount()) != 1) {
            throw new BizIllegalException("余额不足或账户不存在");
        }
        record(command, BalanceFlow.TYPE_DEDUCT, -command.getAmount());
        payments.transition(command.getKey(), "DEBITED");
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(BalancePaymentCommand command) {
        validate(command);
        BalancePayment payment = lock(command);
        if ("CANCELED".equals(payment.getState()) || "REFUNDED".equals(payment.getState())) return;
        if ("DEBITED".equals(payment.getState())) {
            if (users.refundMoney(command.getUserId(), command.getAmount()) != 1) {
                throw new BizIllegalException("退款账户不存在");
            }
            record(command, BalanceFlow.TYPE_REFUND, command.getAmount());
            payments.transition(command.getKey(), "REFUNDED");
        } else if ("NEW".equals(payment.getState())) {
            payments.transition(command.getKey(), "CANCELED");
        } else {
            throw new BizIllegalException("资金操作状态异常");
        }
    }

    private BalancePayment lock(BalancePaymentCommand command) {
        payments.ensure(command.getKey(), command.getUserId(), command.getAmount());
        BalancePayment payment = payments.lock(command.getKey());
        if (payment == null || !Objects.equals(payment.getUserId(), command.getUserId())
                || !Objects.equals(payment.getAmount(), command.getAmount())) {
            throw new BizIllegalException("相同支付凭据不能修改付款人或金额");
        }
        return payment;
    }

    private void record(BalancePaymentCommand command, String type, int amount) {
        flows.insert(new BalanceFlow().setUserId(command.getUserId()).setBizType(type)
                .setBizNo(command.getKey()).setChangeAmount(amount)
                .setBalanceAfter(users.selectById(command.getUserId()).getBalance())
                .setRemark("余额支付尝试"));
    }

    private void validate(BalancePaymentCommand command) {
        if (command == null || command.getKey() == null || !command.getKey().matches("(?:PAYMENT_V2|VOUCHER_PURCHASE_V1):[0-9]{1,20}")
                || command.getUserId() == null || command.getAmount() == null || command.getAmount() <= 0) {
            throw new BadRequestException("支付凭据、付款人或金额非法");
        }
    }
}
