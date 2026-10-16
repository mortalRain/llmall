package com.llmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付单（pay_order 表）：一笔交易订单在支付域的唯一账本。
 *
 * 【边界约束】金额单位一律为分；biz_order_no 上有唯一键，一笔交易订单只允许存在一条支付单。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("pay_order")
public class PayOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long bizOrderNo;

    private Long payOrderNo;

    private Long bizUserId;

    private String payChannelCode;

    /** 支付金额，单位分 */
    private Integer amount;

    /** 支付类型，取值见 PayType：1 网页支付JS、2 小程序支付、3 APP支付、4 扫码支付、5 余额支付 */
    private Integer payType;

    /** 支付状态，取值见 PayStatus：0 未提交、1 待支付、2 已关闭、3 支付成功 */
    private Integer status;

    private String expandJson;

    /**
     * 结果码：余额支付不写第三方业务码，本列承载对账标记 —— 扣款结果未知时写 UNCERTAIN，
     * 日终对账按它筛出待对账单（见 PayOrderServiceImpl 与 PayReconcileTask）。
     */
    private String resultCode;

    /** 结果说明：扣款结果未知时写入扣款幂等键与失败原因，供人工核对余额流水 */
    private String resultMsg;

    private LocalDateTime paySuccessTime;

    private LocalDateTime payOverTime;

    private String qrCodeUrl;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    /** 逻辑删除 */
    private Boolean isDelete;


}
