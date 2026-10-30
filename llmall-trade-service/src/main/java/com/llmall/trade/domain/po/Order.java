package com.llmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("`order`")
public class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 未付款 */
    public static final int STATUS_UNPAID = 1;
    /** 已付款（待发货/待核销） */
    public static final int STATUS_PAID = 2;
    /** 交易取消、订单关闭 */
    public static final int STATUS_CANCELED = 5;
    /** 已核销（门店套餐/团购券到店使用后） */
    public static final int STATUS_VERIFIED = 7;
    /** Cancellation/refund accepted; remote compensation is durable and retryable. */
    public static final int STATUS_CANCELING = 8;
    public static final int STATUS_REFUNDING = 9;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 1 实物订单 2 门店套餐/团购券订单 */
    private Integer orderType;

    private Long shopId;

    private Long voucherId;

    private String verifyCode;

    private LocalDateTime verifyTime;

    /** 客户端幂等令牌：与 user_id 组成唯一键 uk_user_token，重复提交返回同一张订单 */
    private String clientToken;

    /** 下单参数哈希：用于识别"同一令牌、不同参数"的客户端误用 */
    private String requestHash;

    /** 总金额，单位：分 */
    private Integer totalFee;

    /** 支付类型：1 支付宝 2 微信 3 扣减余额 */
    private Integer paymentType;

    private Long userId;

    /** 订单状态：1 未付款、2 已付款、5 已关闭、7 已核销、8 取消处理中、9 退款处理中 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private LocalDateTime consignTime;

    private LocalDateTime endTime;

    private LocalDateTime closeTime;

    private LocalDateTime commentTime;

    private LocalDateTime updateTime;


}
