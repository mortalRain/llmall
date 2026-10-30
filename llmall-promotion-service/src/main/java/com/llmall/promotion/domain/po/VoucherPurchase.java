package com.llmall.promotion.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

/** Durable ordinary-voucher purchase; password is never part of the persisted state. */
@Data
@Accessors(chain = true)
public class VoucherPurchase {
    public static final String ACTIVE = "ACTIVE", ISSUED = "ISSUED", CANCELING = "CANCELING", CANCELED = "CANCELED";
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long voucherId;
    private String requestId;
    private Integer amount;
    private Long shopId;
    private LocalDateTime expireTime;
    private String state;
    private Long userVoucherId;
    private LocalDateTime createTime;
    private LocalDateTime nextCheckTime;
    public String paymentKey() { return "VOUCHER_PURCHASE_V1:" + id; }
}
