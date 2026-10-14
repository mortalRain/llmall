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
 * 消费"门店热度/文档变更"事件 → 同步搜索视图（本地读模型 + ES），是"评价 → 门店热度 → 搜索排序"闭环的最后一跳。
 *
 * 消费记录闸门（幂等）：先插 consume_record（唯一键 uk_consume(biz_type, biz_id, consumer) 裁决），
 * 插入成功才更新读模型，两者在同一个事务里提交/回滚。MQ 是 at-least-once，重投是常态：
 * 插入冲突即表示已处理过，直接 ack 返回；抛异常则占位随事务回滚消失，消息重投时闸门重新放行，
 * 不会出现"处理失败却留下已处理的痕迹"。这张表同时是门店域对账要问的判据（GET /search/consume-status）。
 *
 * 【注意】异常一律外抛：视图更新失败被静默吞掉的话，搜索排序会长期偏离，而发送方看到的只是"投递成功"。
 * 监听方法上的 {@code @Transactional} 会让内部对 service 的调用加入同一事务，不会另开一个。
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
        // bizId 缺失时兜底（队列里改造前投递的老格式消息或人工补发）：用 shopId + occurredAt 拼确定性键，
        // 保证同一条消息重投时闸门仍然有效。
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
            // 抛出 → 事务回滚 → 消费记录占位一并消失 → 重投时闸门重新放行。
            log.error("同步搜索视图失败, event={}", event, e);
            throw e;
        }
    }
}
