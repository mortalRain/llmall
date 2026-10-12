package com.llmall.user.service;

import com.llmall.api.event.OrderPaidEvent;
import com.llmall.user.domain.po.PointsFlow;

import java.util.List;

public interface IPointsService {

    /**
     * 订单支付后发放积分（幂等）：
     *  - 消息级幂等：consume_record 挡住重复投递；
     *  - 业务级幂等：points_flow 的 (biz_type, biz_id, type) 唯一键是对账判据。
     *
     * @return true 表示本次真正发放了积分，false 表示幂等跳过
     */
    boolean applyOrderPoints(OrderPaidEvent event);

    /** 对账判据：该订单的积分是否已发放 */
    boolean isApplied(Long orderId);

    /** 我的积分流水 */
    List<PointsFlow> listMyFlows(Long userId, Integer limit);
}
