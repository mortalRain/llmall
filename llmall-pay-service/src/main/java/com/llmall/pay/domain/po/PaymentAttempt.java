package com.llmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class PaymentAttempt {
    public static final String ACTIVE="ACTIVE", CANCELING="CANCELING", CANCELED="CANCELED", SUCCEEDED="SUCCEEDED";
    @TableId
    private Long id;
    private Long payOrderId;
    private Long bizOrderNo;
    private Long userId;
    private Integer amount;
    private String state;
    private Boolean notified;
    private LocalDateTime createTime;
    private LocalDateTime nextCheckTime;
    public String debitKey() { return "PAYMENT_V2:" + id; }
}
