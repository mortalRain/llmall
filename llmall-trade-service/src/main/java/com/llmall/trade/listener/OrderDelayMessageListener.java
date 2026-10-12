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
 * 订单超时延迟消息消费：到点后判断"到底付没付"，再决定关单还是标记已支付。
 *
 * <h3>本类最重要的原则：降级/未知状态绝不能翻译成"未支付"</h3>
 * 原实现在查不到支付单时直接走关单分支，于是 llmall-pay-service 一抖动，
 * 已付款的订单就被关掉并退库存（钱扣了、单没了）。现在的规则是三分支：
 * <ul>
 *   <li>确认已支付 → 标记订单已支付；</li>
 *   <li>确认未支付（含"确认没有支付单"）→ 关单 + 回滚库存；</li>
 *   <li>状态未知（支付服务不可用/查询失败）→ **不关单**，按指数退避重新投递延迟消息，
 *       超过 {@code llmall.trade.order-close-max-retry} 次后只告警、订单保持待付款等人工处理。</li>
 * </ul>
 * 为什么宁可"订单挂着"：挂着最多是库存被占一会儿（有兜底扫描与人工可处理），
 * 而错误关单是用户钱没了、货也没了。两个错误方向的代价不对称，所以选择偏保守的一侧。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDelayMessageListener {

    /**
     * 重新投递时携带的"已重试次数"消息头。
     * 为什么放在消息头而不是数据库：重投的是同一条逻辑消息，只有这条链路自己需要这个计数，
     * 放消息上最简单且不引入新表；进程重启/消息丢失会让计数归零，
     * 而计数归零的后果只是"多查几次支付状态"，不会关错单，可接受。
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
        // 订单不存在 / 已不是待付款 → 无需处理（重复投递的常态）
        OrderCloseGuard.Outcome outcome = orderCloseGuard.settle(orderId);
        if (outcome != OrderCloseGuard.Outcome.UNKNOWN) {
            return;
        }
        redeliverAfterUnknown(orderId, retry);
    }

    /**
     * 支付状态未知 → 重新投递延迟消息（指数退避），而不是关单。
     * 重投上限由 llmall.trade.order-close-max-retry 控制，默认 5 次。
     */
    private void redeliverAfterUnknown(Long orderId, int retry) {
        if (retry >= orderCloseMaxRetry) {
            // 放弃自动关单并升级为 ERROR：这属于"需要人看一眼"的订单，
            // 而不是"可以默默处理掉"的订单。保持待付款是安全的一侧。
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
