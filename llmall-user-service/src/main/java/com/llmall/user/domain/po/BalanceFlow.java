package com.llmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 余额变动流水（资金账本）。
 *
 * 【注意】唯一键 (biz_type, biz_no) 即幂等键：同一业务号的第二次写入会被唯一键顶掉，
 * 调用方据此判定是否已处理；流水必须与余额变动写在同一个本地事务里。
 * balance_after 为变动后余额，用于与 user.balance 对账。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("balance_flow")
public class BalanceFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 出账（扣款）：change_amount 为负。 */
    public static final String TYPE_DEDUCT = "DEDUCT";

    /** 入账（退款）：change_amount 为正。 */
    public static final String TYPE_REFUND = "REFUND";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 变动金额（分）：正=入账，负=出账 */
    private Integer changeAmount;

    /** 本次变动后的余额（分） */
    private Integer balanceAfter;

    /** DEDUCT / REFUND */
    private String bizType;

    /** 业务号，与 bizType 一起构成幂等键 */
    private String bizNo;

    private String remark;

    private LocalDateTime createTime;
}
