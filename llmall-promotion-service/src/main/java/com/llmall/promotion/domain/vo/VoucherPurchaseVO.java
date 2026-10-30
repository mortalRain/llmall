package com.llmall.promotion.domain.vo;

import com.llmall.promotion.domain.po.VoucherPurchase;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class VoucherPurchaseVO {
    private String requestId;
    private String state;
    private Long voucherId;
    private Integer amount;
    private Long userVoucherId;

    public static VoucherPurchaseVO of(String requestId, VoucherPurchase p) {
        VoucherPurchaseVO result = new VoucherPurchaseVO().setRequestId(requestId).setState("NOT_FOUND");
        return p == null ? result : result.setState(p.getState()).setVoucherId(p.getVoucherId())
                .setAmount(p.getAmount()).setUserVoucherId(p.getUserVoucherId());
    }
}
