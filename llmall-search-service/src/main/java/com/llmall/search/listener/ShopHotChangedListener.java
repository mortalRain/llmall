package com.llmall.search.listener;

import com.llmall.common.constant.MqConstants;
import com.llmall.search.domain.po.ConsumeRecord;
import com.llmall.search.event.ShopDocChangedEvent;
import com.llmall.search.mapper.ConsumeRecordMapper;
import com.llmall.search.service.ISearchService;
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
 * 消费"门店热度/文档变更"事件 → 同步搜索视图（读模型 + ES）。
 *
 * 这是"评价 → 门店热度 → 搜索排序"闭环的**最后一跳**：
 * 内容域产生评价 → 门店域算热度 → 搜索域更新读模型 → 检索结果排序随之变化。
 * 三个域全程通过事件解耦，没有任何直接调用。
 *
 * <h3>审计 B4 后新增的两件事</h3>
 * 1. **消费记录闸门（幂等 + 对账判据）**：先插 consume_record
 *    （唯一键 uk_consume(biz_type, biz_id, consumer) 裁决），插入成功才更新读模型，
 *    两者在**同一个事务**里提交/回滚。MQ 是 at-least-once，重投是常态：
 *    - 插入冲突 → 这条已经处理过，直接 ack 返回；
 *    - 抛异常 → 占位随事务回滚消失 → 消息被容器重投（或门店域对账补发），不会"处理失败却留下已处理的痕迹"。
 * 2. **给门店域对账留下可查询的判据**：门店域的 ShopSearchReconcileTask 会拿
 *    bizType+bizId 来问"这条你处理了吗"，答案就是这张表里有没有那一行（见 GET /search/consume-status）。
 *    没有这个判据，"消息发出去了但下游没处理成功"这类缺口就只能靠猜。
 *
 * <h3>事务边界</h3>
 * 监听方法上加 @Transactional，内部对 service 的调用会加入同一个事务
 * （applyShopHotChanged 上的 @Transactional 是同传播级别，不会另开一个）。
 * 异常一律外抛：视图更新失败绝不能被静默吞掉 —— 否则搜索排序会长期偏离，
 * 而发送方（门店域）看到的只是"消息投递成功"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopHotChangedListener {

    private static final String CONSUMER = "llmall-search-service";

    private final ISearchService searchService;
    private final ConsumeRecordMapper consumeRecordMapper;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.SHOP_HOT_CHANGED_QUEUE, durable = "true"),
            exchange = @Exchange(name = MqConstants.SHOP_EXCHANGE, type = "topic"),
            key = MqConstants.SHOP_HOT_CHANGED_KEY
    ))
    @Transactional(rollbackFor = Exception.class)
    public void onShopHotChanged(ShopDocChangedEvent event) {
        if (event == null || event.getShopId() == null) {
            log.warn("收到非法的 shop.hot.changed 事件: {}", event);
            return;
        }
        // 幂等键缺失时兜底：老格式消息（改造前投递、还在队列里的）或人工补发的消息可能没有 bizId。
        // 用 shopId + occurredAt 拼一个确定性的键，保证同一条消息重投时闸门仍然有效。
        String bizId = event.getBizId() != null ? event.getBizId()
                : event.getShopId() + ":" + (event.getOccurredAt() == null ? 0L : event.getOccurredAt());

        ConsumeRecord record = new ConsumeRecord()
                .setBizType(MqConstants.BIZ_SHOP_HOT_CHANGED)
                .setBizId(bizId)
                .setConsumer(CONSUMER);
        try {
            consumeRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("该门店变更事件已处理过，幂等跳过: bizId={}", bizId);
            return;
        }

        try {
            searchService.applyShopHotChanged(event);
        } catch (Exception e) {
            // 抛出 → 事务回滚 → 上面的占位一并消失 → 消息重投时闸门重新放行。
            // 若吞掉异常，就会出现"消费记录说已处理、读模型其实没更新"的静默缺口。
            log.error("同步搜索视图失败, event={}", event, e);
            throw e;
        }
    }
}
