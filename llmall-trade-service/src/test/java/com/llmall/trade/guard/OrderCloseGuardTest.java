package com.llmall.trade.guard;

import com.llmall.api.client.PayClient;
import com.llmall.api.dto.PayOrderStatusDTO;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.service.IOrderService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCloseGuardTest {
    @Test void processingUnknownAndMalformedResponsesNeverCancel() {
        PayOrderStatusDTO explicitUnknown=PayOrderStatusDTO.of(1);explicitUnknown.setDetermined(false);
        for(PayOrderStatusDTO status:new PayOrderStatusDTO[]{PayOrderStatusDTO.of(4),PayOrderStatusDTO.of(99),
                PayOrderStatusDTO.unknown("timeout"),PayOrderStatusDTO.of(null),explicitUnknown,null}) {
            IOrderService orders=mock(IOrderService.class);PayClient pay=mock(PayClient.class);
            when(orders.getById(100L)).thenReturn(new Order().setId(100L).setStatus(1));
            when(pay.queryPayOrderByBizOrderNo(100L)).thenReturn(status);
            assertEquals(OrderCloseGuard.Outcome.UNKNOWN,new OrderCloseGuard(orders,pay).settle(100L));
            verify(orders,never()).cancelOrder(anyLong());verify(orders,never()).markOrderPaySuccess(anyLong());
        }
    }
    @Test void confirmedPaymentRepairsTradeInsteadOfCanceling() {
        IOrderService orders=mock(IOrderService.class);PayClient pay=mock(PayClient.class);
        when(orders.getById(100L)).thenReturn(new Order().setId(100L).setStatus(1));
        when(pay.queryPayOrderByBizOrderNo(100L)).thenReturn(PayOrderStatusDTO.of(3));
        assertEquals(OrderCloseGuard.Outcome.MARKED_PAID,new OrderCloseGuard(orders,pay).settle(100L));
        verify(orders).markOrderPaySuccess(100L);verify(orders,never()).cancelOrder(anyLong());
    }
}
