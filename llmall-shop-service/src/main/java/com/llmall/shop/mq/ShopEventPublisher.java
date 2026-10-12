package com.llmall.shop.mq;

import com.llmall.api.event.ShopHotChangedEvent;
import com.llmall.common.constant.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 门店域事件发布：热度变更 → 搜索域同步索引（M6 接入）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishHotChanged(Long shopId, Long typeId, Double hotScore, Integer comments, Integer score) {
        ShopHotChangedEvent event = new ShopHotChangedEvent()
                .setBizId(shopId + ":" + System.currentTimeMillis())
                .setShopId(shopId)
                .setTypeId(typeId)
                .setHotScore(hotScore)
                .setComments(comments)
                .setScore(score)
                .setOccurredAt(System.currentTimeMillis());
        try {
            rabbitTemplate.convertAndSend(
                    MqConstants.SHOP_EXCHANGE, MqConstants.SHOP_HOT_CHANGED_KEY, event);
            log.info("已投递 shop.hot.changed 事件: shopId={}, hotScore={}", shopId, hotScore);
        } catch (Exception e) {
            log.error("投递 shop.hot.changed 事件失败, shopId={}", shopId, e);
        }
    }
}
