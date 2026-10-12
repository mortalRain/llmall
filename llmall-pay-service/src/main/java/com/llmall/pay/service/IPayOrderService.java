package com.llmall.pay.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.pay.domain.dto.PayApplyDTO;
import com.llmall.pay.domain.dto.PayOrderFormDTO;
import com.llmall.pay.domain.po.PayOrder;


/**
 * <p>
 * 支付订单 服务类
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-16
 */
public interface IPayOrderService extends IService<PayOrder> {

    String applyPayOrder(PayApplyDTO applyDTO);

    void tryPayOrderByBalance(PayOrderFormDTO payOrderFormDTO);

    /**
     * 关闭支付单（0/1/3 → 2），供交易域的"已支付订单退款"闭环回写支付账本。
     *
     * 返回"是否真的发生了状态变更"：已经是关闭的、或压根没有支付单时为 false，
     * 调用方据此只做日志，不把退款判成失败 —— 支付账本与交易账本的收敛点不该由"调用方猜"。
     *
     * @param bizOrderNo 交易订单号
     * @return true 表示本次把支付单改成了"已关闭"
     */
    boolean closePayOrder(Long bizOrderNo);
}
