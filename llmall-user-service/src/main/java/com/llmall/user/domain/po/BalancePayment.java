package com.llmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("balance_payment")
public class BalancePayment {
    @TableId
    private String paymentKey;
    private Long userId;
    private Integer amount;
    private String state;
}
