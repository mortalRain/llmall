package com.llmall.shop.mq;

import com.llmall.common.constant.MqConstants;
import com.llmall.shop.event.ShopDocChangedEvent;
import com.llmall.shop.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 门店域事件发布：热度/门店文档变更 → 搜索域同步索引（M6 接入）。
 *
 * <h3>改前（审计 B4）</h3>
 * 原实现直接在业务事务里 {@code rabbitTemplate.convertAndSend(...)}，失败只
 * {@code log.error("投递 shop.hot.changed 事件失败")}。于是：
 *  - 事务回滚了，事件可能已经发出去；事务提交了、投递前进程挂掉，事件又永远发不出去；
 *  - MQ 抖动一次 = "评价存在、热度不变"，**没有重试、没有对账、没有任何信号**。
 *
 * <h3>改后</h3>
 * 发布动作拆成"同事务登记 + 提交后投递"两步（与 llmall-trade 的可靠消息范式一致）：
 *  1. {@link IMessageLogService#record}：把事件写进 llmall-shop.message_log ——
 *     与业务写（shop 行的热度/评分/销量）在**同一个本地事务**里提交，业务成功 ⇒ 消息一定存在；
 *  2. {@link IMessageLogService#dispatchAfterCommit}：提交之后才真正 convertAndSend；
 *     投递失败会被记成 FAILED + 退避时间，由 MessageRetryTask 定时重发；
 *     下游（搜索域）消费幂等，重复投递安全。
 *
 * 本类现在**不再直接持有 RabbitTemplate**：投递这件事只允许走本地消息表，
 * 从结构上杜绝"顺手在事务里直发一条事件"的写法再次出现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopEventPublisher {

    private final IMessageLogService messageLogService;

    /**
     * 发布"门店文档变更"事件（路由键 shop.hot.changed，事件体是整行门店快照）。
     *
     * @param event 调用方（ShopServiceImpl#recomputeHotScore）用**同一事务内读到的** shop 行构造；
     *              bizId / occurredAt 由本方法统一填，保证"幂等键"与"乱序比较用的时间"只有一个来源。
     */
    public void publishHotChanged(ShopDocChangedEvent event) {
        if (event == null || event.getShopId() == null) {
            log.warn("忽略非法的门店变更事件: {}", event);
            return;
        }
        long now = System.currentTimeMillis();
        // 幂等键 = 门店 id + 毫秒时间戳：
        // 只用门店 id 当键会让 uk_biz 把同一门店后续的变更全部判重忽略；
        // 带上时间戳后每次变更一条，且搜索域用它写 consume_record，对账任务能逐条问"处理了没有"。
        String bizId = event.getShopId() + ":" + now;
        event.setBizId(bizId);
        event.setOccurredAt(now);

        // ① 同事务落库（调用方带着事务进来；万一没有事务，record 会自己开一个短事务，仍然是先落库再投递）
        messageLogService.record(MqConstants.BIZ_SHOP_HOT_CHANGED, bizId,
                MqConstants.SHOP_EXCHANGE, MqConstants.SHOP_HOT_CHANGED_KEY, event);
        // ② 提交后投递
        messageLogService.dispatchAfterCommit(MqConstants.BIZ_SHOP_HOT_CHANGED, bizId);
        log.info("已登记门店文档变更事件（提交后投递）: shopId={}, sold={}, hotScore={}, bizId={}",
                event.getShopId(), event.getSold(), event.getHotScore(), bizId);
    }
}
