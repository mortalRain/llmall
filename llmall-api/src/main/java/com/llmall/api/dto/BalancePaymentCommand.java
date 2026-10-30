package com.llmall.api.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** Internal payment command. Password is request-only and must never be persisted or logged. */
@Data
@Accessors(chain = true)
public class BalancePaymentCommand {
    private String key;
    private Long userId;
    private Integer amount;
    @lombok.ToString.Exclude
    private String password;
}
