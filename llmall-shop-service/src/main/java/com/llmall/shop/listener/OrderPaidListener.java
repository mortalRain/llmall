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
 * 这是**扇出（fan-out）**的第二个消费者：同一条 order.paid 消息，llmall-user-service 拿去发积分，
 * llmall-shop-service 拿去加销量，两者互不影响、各自幂等（各自库里各有一份 consume_record）。
 * 这也是用 MQ 解耦的价值：交易域发布一次，后续再加消费者不需要改生产者一行代码。
 *
 * <h3>为什么改成"幂等闸门与业务同事务"，而不是原来的"失败删占位 + 重抛"</h3>
 * MQ 是 at-least-once：同一条消息被投递两次是常态。这个消费者的业务动作是
 * {@code comments+1 / sold+num} 这类**累加**，重复执行不会"报错"，只会把数字悄悄算大 ——
 * 静默错数比抛异常难查得多，所以必须靠 consume_record 的唯一键
 * uk_consume(biz_type, biz_id, consumer) 来裁决"这条消息我到底处理过没有"。
 *
 * 原写法是"先插占位 + 业务包在 try 里，失败就 deleteById 撤销占位再抛异常重试"。
 * 它的问题在于：占位与业务**不在同一个事务**里，
 *  ① 撤销占位这一步自己也可能失败（deleteById 抛异常被 catch 成一条 error 日志），
 *     于是留下"业务没做但占位还在"的记录 —— 这条消息从此永远被当成已处理，数据静默丢失；
 *  ② 反过来，若进程在"业务已提交、占位还没撤销"之间挂掉，重投时占位仍在，
 *     业务会被跳过（这次是安全的）—— 两种结果取决于崩溃点，语义无法收敛。
 *
 * 现在的写法把"占位 + 累加"放进**同一个本地事务**：
 *  - 占位插入成功 → 业务执行 → 提交（占位与累加一起生效，消息被 ack）；
 *  - 业务抛异常 → 整个事务回滚，**占位自动消失**，消息被容器重投，
 *    重投时闸门放行、业务重新执行一次 —— 既不会重复累加，也不会永久丢消息。
 * 这样就不需要"手工撤销占位"这段脆弱逻辑了：数据库的事务本身就是撤销机制。
 *
 * 失败分类处理（重要取舍）：
 *  - **业务失败**（订单没有门店、门店不存在这类"重试一万次结果也一样"的情况）：
 *    记日志后正常返回，占位随事务提交保留 = 直接 ack，不再重试。
 *    否则消息会在重试队列里空转，最终进死信，纯粹浪费资源。
 *  - **系统异常**（数据库/Redis 抖动）：不捕获、让它抛出，交给容器重试。
 *    幂等闸门保证重试不会把销量累加两次。
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

        // 1. 幂等闸门：唯一键 uk_consume 直接裁决（不做"先查再插"，并发下那个窗口会双扣销量）
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

        // 2. 业务处理（与上面的占位处于同一事务：要么一起生效，要么一起回滚）
        if (event.getShopId() == null) {
            // 【业务失败】实物订单没有门店，属于正常情况，重试也不会有门店：
            // 占位保留并正常返回 = ack，不再重试
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
            // 【业务失败】门店不存在（脏数据），重试无意义：记日志 + ack，占位保留避免无限重试
            log.warn("门店不存在，无法累加销量（不再重试）: shopId={}, orderId={}", event.getShopId(), bizId);
            return;
        }
        log.info("门店销量已累加: shopId={}, num={}, orderId={}", event.getShopId(), num, bizId);
        // 任何未捕获的系统异常都会向上抛出 → 事务回滚（占位一并消失）→ 容器重投，
        // 重投时闸门重新放行，因此不会出现"重复累加"。
    }
}
