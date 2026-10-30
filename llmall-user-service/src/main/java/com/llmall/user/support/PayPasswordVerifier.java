package com.llmall.user.support;

import com.llmall.common.exception.BizIllegalException;
import com.llmall.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import static com.llmall.user.support.PasswordAttemptGuard.Purpose.PAYMENT;

/** Shared by legacy debit, durable debit and payment-password changes. */
@Component
@RequiredArgsConstructor
public class PayPasswordVerifier {
    private final PasswordAttemptGuard attempts;
    private final UserMapper users;
    private final PasswordEncoder passwords;

    public void verifyForPayment(Long userId, String password) {
        String hash = verifyForChange(userId, password);
        if (passwords.matches("123456", hash)) {
            throw new BizIllegalException("请先修改初始支付密码");
        }
    }

    public String verifyForChange(Long userId, String password) {
        attempts.check(userId, PAYMENT);
        String hash = users.selectPayPassword(userId);
        if (hash == null || password == null || password.isEmpty() || !passwords.matches(password, hash)) {
            attempts.failure(userId, PAYMENT);
            throw new BizIllegalException("支付密码错误");
        }
        // SUCCESS rechecks the lock atomically: a concurrent fifth failure cannot be cleared.
        attempts.success(userId, PAYMENT);
        return hash;
    }
}
