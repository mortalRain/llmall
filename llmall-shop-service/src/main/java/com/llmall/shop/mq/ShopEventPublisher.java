package com.llmall.shop.mq;

import com.llmall.common.constant.MqConstants;
import com.llmall.shop.event.ShopDocChangedEvent;
import com.llmall.shop.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 门店域事件发布：热度/门店文档变更 → 搜索域同步索引。
 *
 * 【注意】只允许"同事务登记 + 提交后投递"这条路径（本类不持有 RabbitTemplate）：
 * {@link IMessageLogService#record} 把事件与业务写在同一个本地事务里落库，业务成功 ⇒ 消息一定存在；
 * {@link IMessageLogService#dispatchAfterCommit} 提交后才真正发送，失败记成 FAILED + 退避时间由
 * MessageRetryTask 重发，下游消费幂等，重复投递安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopEventPublisher {

    private final IMessageLogService messageLogService;

    /**
     * 发布"门店文档变更"事件（路由键 shop.hot.changed，事件体是整行门店快照）。
     *
     * @param event 调用方（ShopServiceImpl#recomputeHotScore）用同一事务内读到的 shop 行构造；
     *              bizId / occurredAt 由本方法统一填，保证"幂等键"与"乱序比较用的时间"只有一个来源
     */
    public void publishHotChanged(ShopDocChangedEvent event) {
        if (event == null || event.getShopId() == null) {
            log.warn("忽略非法的门店变更事件: {}", event);
            return;
        }
        long now = System.currentTimeMillis();
        // 幂等键 = 门店 id + 毫秒时间戳：只用门店 id 会被 uk_biz 把同一门店后续的变更全部判重忽略，
        // 带上时间戳则每次变更一条，搜索域用它写 consume_record，对账任务能逐条问"处理了没有"。
        String bizId = event.getShopId() + ":" + now;
        event.setBizId(bizId);
        event.setOccurredAt(now);

        // 同事务落库：调用方带着事务进来；万一没有事务，record 会自己开一个短事务，仍是先落库再投递
        messageLogService.record(MqConstants.BIZ_SHOP_HOT_CHANGED, bizId,
                MqConstants.SHOP_EXCHANGE, MqConstants.SHOP_HOT_CHANGED_KEY, event);
        // 提交后投递
        messageLogService.dispatchAfterCommit(MqConstants.BIZ_SHOP_HOT_CHANGED, bizId);
        log.info("已登记门店文档变更事件（提交后投递）: shopId={}, sold={}, hotScore={}, bizId={}",
                event.getShopId(), event.getSold(), event.getHotScore(), bizId);
    }
}
