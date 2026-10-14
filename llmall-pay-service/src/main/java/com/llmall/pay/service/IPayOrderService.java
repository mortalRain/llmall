package com.llmall.pay.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.pay.domain.dto.PayApplyDTO;
import com.llmall.pay.domain.dto.PayOrderFormDTO;
import com.llmall.pay.domain.po.PayOrder;


/** 支付单的申请、余额支付与关闭。 */
public interface IPayOrderService extends IService<PayOrder> {

    String applyPayOrder(PayApplyDTO applyDTO);

    void tryPayOrderByBalance(PayOrderFormDTO payOrderFormDTO);

    /**
     * 关闭支付单（0/1/3 → 2），供交易域的"已支付订单退款"闭环回写支付账本。
     *
     * @param bizOrderNo 交易订单号
     * @return true 表示本次确实将状态置为"已关闭"；已是关闭状态或无支付单时为 false，
     *         调用方据此只记日志，不把退款判成失败
     */
    boolean closePayOrder(Long bizOrderNo);
}
