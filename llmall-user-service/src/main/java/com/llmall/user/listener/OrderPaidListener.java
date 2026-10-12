package com.llmall.user.listener;

import com.llmall.api.event.OrderPaidEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.user.service.IPointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费"订单支付成功"事件 → 发放积分。
 *
 * 消息可靠性不在消费者这一侧保证（那是生产者的本地消息表 + 重试 + 对账的职责），
 * 消费者只需做到两件事：**幂等**（重复投递不出错）与**失败可见**（抛异常触发重试/死信）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaidListener {

    private final IPointsService pointsService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.ORDER_PAID_USER_QUEUE, durable = "true"),
            exchange = @Exchange(name = MqConstants.TRADE_EXCHANGE, type = "topic"),
            key = MqConstants.ORDER_PAID_KEY
    ))
    public void onOrderPaid(OrderPaidEvent event) {
        boolean applied = pointsService.applyOrderPoints(event);
        if (!applied) {
            log.info("订单积分未发放（幂等跳过或金额不足）: orderId={}",
                    event == null ? null : event.getOrderId());
        }
    }
}
