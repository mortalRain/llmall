package com.llmall.search.listener;

import com.llmall.api.event.ShopHotChangedEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费"门店热度变更"事件 → 同步搜索视图。
 *
 * 这是"评价 → 门店热度 → 搜索排序"闭环的**最后一跳**：
 * 内容域产生评价 → 门店域算热度 → 搜索域更新读模型 → 检索结果排序随之变化。
 * 三个域全程通过事件解耦，没有任何直接调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopHotChangedListener {

    private final ISearchService searchService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.SHOP_HOT_CHANGED_QUEUE, durable = "true"),
            exchange = @Exchange(name = MqConstants.SHOP_EXCHANGE, type = "topic"),
            key = MqConstants.SHOP_HOT_CHANGED_KEY
    ))
    public void onShopHotChanged(ShopHotChangedEvent event) {
        try {
            searchService.applyShopHotChanged(event);
        } catch (Exception e) {
            // 抛出让 MQ 重试：视图更新失败不该被静默吞掉，否则搜索排序会长期偏离
            log.error("同步搜索视图失败, event={}", event, e);
            throw e;
        }
    }
}
