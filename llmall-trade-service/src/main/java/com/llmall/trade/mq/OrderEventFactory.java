package com.llmall.trade.mq;

import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.api.event.OrderPaidEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.domain.po.OrderDetail;
import com.llmall.trade.service.IOrderDetailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单事件工厂。
 *
 * 抽出来的原因：同一个"订单已支付"事件有两个产生时机——
 * ① 支付回调（正常路径）；② 对账任务发现下游漏处理（补偿路径）。
 * 两处必须产出**完全一致**的消息体，否则补偿重发的消息可能被下游当成不同的业务处理。
 */
@Component
@RequiredArgsConstructor
public class OrderEventFactory {

    private final IOrderDetailService detailService;

    public OrderPaidEvent buildPaidEvent(Order order) {
        List<OrderDetail> details = detailService.listByOrderId(order.getId());
        List<OrderDetailDTO> items = new ArrayList<>();
        if (details != null) {
            for (OrderDetail detail : details) {
                items.add(new OrderDetailDTO()
                        .setItemId(detail.getItemId())
                        .setNum(detail.getNum())
                        .setShopId(order.getShopId())
                        .setBizType(MqConstants.BIZ_ORDER_PAID)
                        .setBizId(String.valueOf(order.getId())));
            }
        }
        return new OrderPaidEvent()
                .setBizId(String.valueOf(order.getId()))
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setTotalFee(order.getTotalFee())
                .setOrderType(order.getOrderType())
                .setShopId(order.getShopId())
                .setItems(items)
                .setOccurredAt(System.currentTimeMillis());
    }
}
