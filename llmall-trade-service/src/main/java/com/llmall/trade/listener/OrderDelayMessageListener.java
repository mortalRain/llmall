package com.llmall.trade.listener;

import com.llmall.common.constant.MqConstants;
import com.llmall.trade.guard.OrderCloseGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 订单超时延迟消息消费：到点后判断订单是否已支付，再决定关单还是标记已支付。
 *
 * 【注意】支付状态未知（支付服务不可用 / 查询失败）不等于未支付，三分支口径为：
 * 已支付 → 标记已支付；确认未支付（含"确认没有支付单"）→ 关单 + 回滚库存；
 * 未知 → 不关单，按指数退避重新投递，超过 {@code llmall.trade.order-close-max-retry}
 * 次后只告警、订单保持待付款等人工处理。错误关单（用户钱货两失）的代价高于订单多挂一会儿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDelayMessageListener {

    /**
     * 重新投递时携带的"已重试次数"消息头。
     *
     * 【注意】计数随消息走、不落库（只有这条重投链路需要它）：进程重启或消息丢失会让计数归零，
     * 而归零只会多查几次支付状态，不会关错单。
     */
    public static final String HEADER_CLOSE_RETRY = "x-order-close-retry";

    /** 重投退避基数（秒）：第 n 次重投 = base * 2^n，上限 CLOSE_RETRY_MAX_SECONDS */
    private static final long CLOSE_RETRY_BASE_SECONDS = 5L;
    private static final long CLOSE_RETRY_MAX_SECONDS = 300L;

    private final OrderCloseGuard orderCloseGuard;
    private final RabbitTemplate rabbitTemplate;

    /** 支付状态未知时的最大重投次数，默认 5（llmall.trade.order-close-max-retry） */
    @Value("${llmall.trade.order-close-max-retry:5}")
    private int orderCloseMaxRetry;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.DELAY_ORDER_QUEUE),
            exchange = @Exchange(name = MqConstants.DELAY_EXCHANGE, delayed = "true"),
            key = MqConstants.DELAY_ORDER_KEY
    ))
    public void listenOrderDelayMessage(Long orderId,
                                        @Header(name = HEADER_CLOSE_RETRY, required = false) Integer retryCount) {
        int retry = retryCount == null ? 0 : retryCount;
        // 订单不存在或已不是待付款 → 无需处理（重复投递的常态）
        OrderCloseGuard.Outcome outcome = orderCloseGuard.settle(orderId);
        if (outcome != OrderCloseGuard.Outcome.UNKNOWN) {
            return;
        }
        redeliverAfterUnknown(orderId, retry);
    }

    /** 支付状态未知：按指数退避重新投递延迟消息而不关单，重投上限见 llmall.trade.order-close-max-retry */
    private void redeliverAfterUnknown(Long orderId, int retry) {
        if (retry >= orderCloseMaxRetry) {
            // 重投达上限：停止自动关单并升级为 ERROR，保持待付款是安全的一侧
            log.error("【需人工介入】订单支付状态连续 {} 次无法确认，已停止自动关单，订单保持待付款: orderId={}",
                    retry, orderId);
            return;
        }
        int next = retry + 1;
        long delaySeconds = Math.min(CLOSE_RETRY_BASE_SECONDS * (1L << Math.min(next, 10)), CLOSE_RETRY_MAX_SECONDS);
        try {
            rabbitTemplate.convertAndSend(MqConstants.DELAY_EXCHANGE, MqConstants.DELAY_ORDER_KEY, orderId,
                    (MessagePostProcessor) message -> {
                        message.getMessageProperties().setDelay((int) Math.min(delaySeconds * 1000L, Integer.MAX_VALUE));
                        message.getMessageProperties().setHeader(HEADER_CLOSE_RETRY, next);
                        return message;
                    });
            log.warn("支付状态未知，不关单，已重新投递延迟消息(第 {} 次，{} 秒后): orderId={}", next, delaySeconds, orderId);
        } catch (Exception e) {
            // 重投失败也不能关单：订单继续挂着，由兜底扫描任务下轮再判断
            log.error("支付状态未知且重新投递失败，订单保持待付款，等待兜底扫描: orderId={}", orderId, e);
        }
    }
}
