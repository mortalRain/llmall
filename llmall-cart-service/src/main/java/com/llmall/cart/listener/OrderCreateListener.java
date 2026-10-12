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
 * 【两个修复】
 * 1. 字段必须是 final：类上用了 @RequiredArgsConstructor，而 Lombok 只把 final 字段写进构造器。
 *    原来写成 `private ICartService cartService;`（非 final、也没 @Autowired），
 *    这个字段永远是 null —— 消息一到就 NPE，而且只在消费时才会暴露。
 * 2. userId 从消息体里取，不能依赖 UserContext：
 *    MQ 监听线程没有登录上下文（ThreadLocal 由网关请求线程写入），
 *    用 UserContext.getUser() 过滤会恒等于 eq(user_id, null)，购物车静默清不掉。
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
