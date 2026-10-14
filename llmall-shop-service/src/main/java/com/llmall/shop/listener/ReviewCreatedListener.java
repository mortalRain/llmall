package com.llmall.shop.listener;

import com.llmall.api.event.ReviewCreatedEvent;
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
 * 消费"评价产生"事件，更新门店评分/评价数并重算热度分；内容域只管发评价，两个域之间没有任何直接调用。
 *
 * 【注意】幂等口径：先插入消费占位，由唯一键 uk_consume(biz_type,biz_id,consumer) 裁决，插入成功才执行
 * 业务累加，冲突直接返回。绝不能用"先 selectCount 查一下有没有"代替 —— 并发/重投下两个线程都查不到，
 * 于是 comments 被加两次，而且不会有任何报错（累加型操作错了也不报错，只能靠闸门）。
 * 占位与业务同事务（本方法带 @Transactional）：抛异常 → 事务回滚 → 占位自动消失 → 容器重投 → 闸门放行，
 * 因此不需要"失败时删除占位"的逻辑，也不会因占位残留而永久丢消息。
 * 失败分类：业务失败（门店不存在等）记日志 + ack 不再重试；系统异常抛出交给容器重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewCreatedListener {

    private static final String CONSUMER = "llmall-shop-service";

    private final IShopService shopService;
    private final ConsumeRecordMapper consumeRecordMapper;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.REVIEW_CREATED_QUEUE, durable = "true"),
            exchange = @Exchange(name = MqConstants.CONTENT_EXCHANGE, type = "topic"),
            key = MqConstants.REVIEW_CREATED_KEY
    ))
    @Transactional(rollbackFor = Exception.class)
    public void onReviewCreated(ReviewCreatedEvent event) {
        if (event == null || event.getShopId() == null || event.getScore() == null) {
            log.warn("收到非法的 review.created 事件: {}", event);
            return;
        }
        String bizId = event.getBizId() != null ? event.getBizId() : String.valueOf(event.getBlogId());

        // 1. 幂等闸门
        ConsumeRecord record = new ConsumeRecord()
                .setBizType(MqConstants.BIZ_REVIEW_CREATED)
                .setBizId(bizId)
                .setConsumer(CONSUMER);
        try {
            consumeRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("该评价已处理过，幂等跳过: bizId={}", bizId);
            return;
        }

        // 2. 业务处理：评分/评价数累加（comments+1 在 applyReview 的 SQL 里完成）
        int updated = shopService.applyReview(event.getShopId(), event.getScore());
        if (updated == 0) {
            // 【业务失败】门店不存在属于脏数据，重试一万次结果也一样：占位随事务提交保留 → 直接 ack，
            // 不再重试（否则只会把消息空转到死信队列，而那里不挂消费者，最终仍需人工处理）。
            log.warn("评价对应的门店不存在（不再重试，占位保留）: shopId={}, bizId={}", event.getShopId(), bizId);
            return;
        }
        // 3. 重算热度（写入 DB + 榜单 ZSet）：
        //    "通知搜索域"已内聚进 recomputeHotScore —— 它把整行门店快照登记进本地消息表（与本次事务同提交），
        //    提交后才投递，失败退避重试 + 定时对账补发；因此本监听器不必持有发布器，新增改热度的调用方也不会漏发事件。
        double hotScore = shopService.recomputeHotScore(event.getShopId());
        log.info("门店热度已更新: shopId={}, hotScore={}", event.getShopId(), hotScore);
    }
}
