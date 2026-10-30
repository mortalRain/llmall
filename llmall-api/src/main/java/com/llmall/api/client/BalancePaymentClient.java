package com.llmall.api.client;

import com.llmall.api.dto.BalancePaymentCommand;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "llmall-user-service", contextId = "balancePaymentClient")
public interface BalancePaymentClient {
    @PostMapping("/internal/balance-payments/debit")
    void debit(@RequestBody BalancePaymentCommand command);

    /** Atomic cancellation: refund a committed debit, or prevent a late debit if none exists. */
    @PostMapping("/internal/balance-payments/cancel")
    void cancel(@RequestBody BalancePaymentCommand command);
}
