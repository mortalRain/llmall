package com.llmall.cart.listener;
import com.llmall.common.constant.MqConstants;
import com.llmall.api.event.CartClearEvent;
import com.llmall.cart.service.ICartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 下单成功后清理购物车。
 *
 * 【注意】依赖字段必须声明为 final：类上用了 @RequiredArgsConstructor，Lombok 只把 final 字段写进构造器，
 * 非 final 字段永远是 null，消息一到就 NPE。
 * userId 只能从消息体取，MQ 监听线程没有登录上下文，用 UserContext 过滤会恒等于 eq(user_id, null)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateListener {

    private final ICartService cartService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.CART_CLEAR_QUEUE, durable = "true"),
            exchange = @Exchange(name = MqConstants.TRADE_EXCHANGE, type = "topic"),
            key = MqConstants.ORDER_CREATE_KEY
    ))
    public void listenOrderCreateSuccess(CartClearEvent event) {
        if (event == null || event.getUserId() == null) {
            log.warn("下单事件缺少 userId，忽略: {}", event);
            return;
        }
        cartService.removeByItemIds(event.getUserId(), event.getItemIds());
        log.info("已清理购物车: userId={}, itemIds={}", event.getUserId(), event.getItemIds());
    }
}
