package com.llmall.pay.controller;

import com.llmall.api.dto.PayOrderStatusDTO;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.pay.service.impl.PayCloseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class InternalPayController {
    private final PayCloseService service;
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    @PostMapping("/internal/pay-orders/close-unpaid")
    public PayOrderStatusDTO closeUnpaid(@RequestBody TradeOrderDTO order,
            @RequestHeader(value=AuthConstants.HEADER_SERVICE_TOKEN, required=false) String token) {
        if (!StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) throw new ForbiddenException("内部接口，禁止外部调用");
        return service.closeUnpaid(order);
    }
}
