package com.llmall.user.controller;

import com.llmall.api.dto.BalancePaymentCommand;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.user.service.impl.BalancePaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/balance-payments")
@RequiredArgsConstructor
public class InternalPaymentController {
    private final BalancePaymentService service;
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    @PostMapping("/debit")
    public void debit(@RequestBody BalancePaymentCommand command,
            @RequestHeader(value=AuthConstants.HEADER_SERVICE_TOKEN, required=false) String token) {
        authenticate(token);
        service.debit(command);
    }

    @PostMapping("/cancel")
    public void cancel(@RequestBody BalancePaymentCommand command,
            @RequestHeader(value=AuthConstants.HEADER_SERVICE_TOKEN, required=false) String token) {
        authenticate(token);
        service.cancel(command);
    }

    private void authenticate(String token) {
        if (!StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) {
            throw new ForbiddenException("内部接口，禁止外部调用");
        }
    }
}
