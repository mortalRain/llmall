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
 * 订单事件工厂：为"订单已支付"事件构造统一消息体。
 *
 * 【注意】支付回调与对账补偿两处产生时机必须产出完全一致的消息体，
 * 否则补偿重发的消息可能被下游当成不同的业务处理。
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
