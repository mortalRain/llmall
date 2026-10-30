package com.llmall.pay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.llmall.api.dto.PayOrderStatusDTO;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.pay.domain.po.PayOrder;
import com.llmall.pay.mapper.PayOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PayCloseService {
    private final PayOrderMapper orders;

    /** A closed placeholder fences concurrent first-time payment creation as well as existing payments. */
    @Transactional(rollbackFor = Exception.class)
    public PayOrderStatusDTO closeUnpaid(TradeOrderDTO trade) {
        if (trade == null || trade.getId() == null || trade.getUserId() == null || trade.getTotalFee() == null) {
            throw new BadRequestException("订单信息不完整");
        }
        PayOrder candidate = new PayOrder().setId(IdWorker.getId()).setPayOrderNo(IdWorker.getId())
                .setBizOrderNo(trade.getId()).setBizUserId(trade.getUserId()).setAmount(trade.getTotalFee())
                .setStatus(2).setPayType(5).setPayChannelCode("balance").setPayOverTime(LocalDateTime.now());
        orders.saveOrGetExisting(candidate);
        PayOrder existing = orders.selectOne(new QueryWrapper<PayOrder>().eq("biz_order_no", trade.getId()));
        if (existing == null) throw new BizIllegalException("支付账本不存在");
        existing = orders.lockById(existing.getId());
        if (!Objects.equals(existing.getBizUserId(), trade.getUserId())
                || !Objects.equals(existing.getAmount(), trade.getTotalFee())) throw new BizIllegalException("支付账本与订单不一致");
        Integer status = existing.getStatus();
        if ("UNCERTAIN".equals(existing.getResultCode())) return PayOrderStatusDTO.unknown("支付待核对");
        if (Integer.valueOf(0).equals(status) || Integer.valueOf(1).equals(status)) {
            orders.updateById(new PayOrder().setId(existing.getId()).setStatus(2));
            status = 2;
        }
        return Integer.valueOf(4).equals(status) ? PayOrderStatusDTO.unknown("支付处理中") : PayOrderStatusDTO.of(status);
    }
}
