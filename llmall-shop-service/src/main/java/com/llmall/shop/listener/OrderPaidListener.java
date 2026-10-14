package com.llmall.shop.listener;

import com.llmall.api.event.OrderPaidEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.shop.domain.po.ConsumeRecord;
import com.llmall.shop.mapper.ConsumeRecordMapper;
import com.llmall.shop.service.IShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费"订单支付成功"事件 → 累加门店销量并重算热度。
 *
 * 【注意】MQ 是 at-least-once，而本消费者做的是 {@code sold+num} 这类累加，重复执行不会报错、
 * 只会静默把数字算大；因此以 consume_record 的唯一键
 * uk_consume(biz_type, biz_id, consumer) 作幂等闸门，且占位与业务动作在同一个本地事务里：
 * 业务抛异常则占位一起回滚，容器重投可重新执行 —— 既不会重复累加，也不会永久丢消息。
 *
 * ack 口径：业务失败（订单无门店、门店不存在，重试一万次结果一样）记日志后正常返回 = ack，
 * 占位随提交保留，不再重试；系统异常不捕获，抛出交给容器重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaidListener {

    private static final String CONSUMER = "llmall-shop-service";

    private final IShopService shopService;
    private final ConsumeRecordMapper consumeRecordMapper;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.ORDER_PAID_SHOP_QUEUE, durable = "true"),
            exchange = @Exchange(name = MqConstants.TRADE_EXCHANGE, type = "topic"),
            key = MqConstants.ORDER_PAID_KEY
    ))
    @Transactional(rollbackFor = Exception.class)
    public void onOrderPaid(OrderPaidEvent event) {
        if (event == null || event.getOrderId() == null) {
            log.warn("收到非法的 order.paid 事件: {}", event);
            return;
        }
        String bizId = String.valueOf(event.getOrderId());

        // 幂等闸门：靠唯一键 uk_consume 直接裁决，不能"先查再插"（并发窗口里会双扣销量）
        ConsumeRecord record = new ConsumeRecord()
                .setBizType(MqConstants.BIZ_ORDER_PAID)
                .setBizId(bizId)
                .setConsumer(CONSUMER);
        try {
            consumeRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("该订单已在门店域处理过，幂等跳过: orderId={}", bizId);
            return;
        }

        if (event.getShopId() == null) {
            log.info("订单无门店信息，跳过门店销量累加: orderId={}", bizId);
            return;
        }
        int num = 1;
        if (event.getItems() != null && !event.getItems().isEmpty()) {
            num = event.getItems().stream()
                    .mapToInt(item -> item.getNum() == null ? 0 : item.getNum())
                    .sum();
            num = Math.max(num, 1);
        }
        int rows = shopService.applyOrderSold(event.getShopId(), num);
        if (rows == 0) {
            log.warn("门店不存在，无法累加销量（不再重试）: shopId={}, orderId={}", event.getShopId(), bizId);
            return;
        }
        log.info("门店销量已累加: shopId={}, num={}, orderId={}", event.getShopId(), num, bizId);
    }
}
