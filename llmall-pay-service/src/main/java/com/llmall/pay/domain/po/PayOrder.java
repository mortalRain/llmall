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
 * 支付订单。
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

    /** 支付类型，1：h5，2：小程序，3：公众号，4：扫码，5：余额支付 */
    private Integer payType;

    /** 支付状态，0：待提交，1：待支付，2：支付超时或取消，3：支付成功 */
    private Integer status;

    private String expandJson;

    private String resultCode;

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
