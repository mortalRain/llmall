package com.llmall.shop.listener;

import com.llmall.api.event.ReviewCreatedEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.shop.domain.po.ConsumeRecord;
import com.llmall.shop.domain.po.Shop;
import com.llmall.shop.mapper.ConsumeRecordMapper;
import com.llmall.shop.mq.ShopEventPublisher;
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
 * 消费"评价产生"事件，更新门店评分/评价数并重算热度分。
 *
 * 这就是亮点 C 的关键一跳：内容域只管发评价，门店域负责把它折算成"热度"，
 * 两个域之间没有任何直接调用（不知道对方存在），只通过事件耦合。
 *
 * <h3>幂等策略（与 OrderPaidListener 同一套，见那边的详细论证）</h3>
 *  - **幂等先行**：先插入消费占位（唯一键 uk_consume(biz_type,biz_id,consumer) 裁决），
 *    插入成功才执行业务累加；插入冲突直接返回，绝不重复执行。
 *    这里绝不能用"先 selectCount 查一下有没有"来代替 —— 并发/重投下两个线程都查不到，
 *    于是 comments 被加两次，而且不会有任何报错（累加型操作错了也不报错，只能靠闸门）。
 *  - **占位与业务同事务**：本方法加了 @Transactional，占位插入与 applyReview/recomputeHotScore
 *    一起提交或一起回滚。因此**不再需要"失败时删除占位"那段逻辑**：
 *    抛异常 → 事务回滚 → 占位自动消失 → 容器重投 → 闸门放行、重新执行，
 *    既不会重复累加，也不会因为"占位留下了"而永久丢消息。
 *  - **失败分类**：业务失败（门店不存在等）记日志 + ack 不再重试；系统异常抛出交给容器重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewCreatedListener {

    private static final String CONSUMER = "llmall-shop-service";

    private final IShopService shopService;
    private final ConsumeRecordMapper consumeRecordMapper;
    private final ShopEventPublisher eventPublisher;

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

        // 2. 业务处理：评分/评价数累加（comments+1 就在 applyReview 的 SQL 里）
        int updated = shopService.applyReview(event.getShopId(), event.getScore());
        if (updated == 0) {
            // 【业务失败】门店不存在：属于脏数据，重试一万次结果也一样。
            // 占位随事务提交保留 → 直接 ack，不再重试（否则消息会在重试队列里空转到死信）。
            log.warn("评价对应的门店不存在（不再重试，占位保留）: shopId={}, bizId={}", event.getShopId(), bizId);
            return;
        }
        // 3. 重算热度（写入 DB + 榜单 ZSet），并通知搜索域
        double hotScore = shopService.recomputeHotScore(event.getShopId());
        Shop shop = shopService.getById(event.getShopId());
        if (shop != null) {
            eventPublisher.publishHotChanged(shop.getId(), shop.getTypeId(), hotScore,
                    shop.getComments(), shop.getScore());
        }
        log.info("门店热度已更新: shopId={}, hotScore={}", event.getShopId(), hotScore);
        // 未捕获的系统异常 → 抛出 → 事务回滚（占位消失）→ 容器重投 → 闸门放行，
        // 由唯一键保证"重投不会把评价数累加两次"。
    }
}
