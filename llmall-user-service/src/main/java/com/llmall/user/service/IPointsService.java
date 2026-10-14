package com.llmall.user.service;

import com.llmall.api.event.OrderPaidEvent;
import com.llmall.user.domain.po.PointsFlow;

import java.util.List;

public interface IPointsService {

    /**
     * 订单支付后发放积分（幂等，重复投递与重复发放都必须挡住）。
     *
     * @return true 表示本次真正发放了积分，false 表示幂等跳过
     */
    boolean applyOrderPoints(OrderPaidEvent event);

    /** 对账判据：该订单的积分是否已发放 */
    boolean isApplied(Long orderId);

    /** 我的积分流水 */
    List<PointsFlow> listMyFlows(Long userId, Integer limit);
}
