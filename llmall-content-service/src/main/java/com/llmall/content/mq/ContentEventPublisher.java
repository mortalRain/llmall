package com.llmall.content.mq;

import com.llmall.api.event.ReviewCreatedEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.content.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内容域事件发布：评价产生 → 门店域更新热度。
 *
 * <h3>改前（审计 B4 生产端）</h3>
 * 原实现（本类）是：
 * <pre>
 * try {
 *     rabbitTemplate.convertAndSend(CONTENT_EXCHANGE, REVIEW_CREATED_KEY, event);   // 已是提交后（调用方套了 TxAfterCommit）
 *     log.info("已投递 review.created 事件 ...");
 * } catch (Exception e) {
 *     log.error("投递 review.created 事件失败, event={}", event, e);                 // ← 事件就在这里永久消失
 * }
 * </pre>
 * 时序是对的（不动事务内直发），但可靠性是零：MQ 抖动一次或提交后进程崩溃，
 * 这条评价就**再也不会**通知到门店域，"评价存在、门店热度不变"可以永久存在，且没有任何信号。
 *
 * <h3>改后：登记与投递分离（与 llmall-shop 的 ShopEventPublisher 同一套）</h3>
 *  1. {@link IMessageLogService#record}：把事件写进 llmall-content.message_log ——
 *     与业务写（blog 行 + feed_inbox）在**同一个本地事务**里提交，业务成功 ⇒ 消息一定存在；
 *  2. {@link IMessageLogService#dispatchAfterCommit}：提交之后才真正 convertAndSend；
 *     **"提交后才发"的语义没有变**，但现在投递失败会被记成 FAILED + 退避时间，
 *     由 MessageRetryTask 定时重发，下游消费幂等（门店域 consume_record 唯一键），重复投递安全。
 *
 * <h3>⚠️ 那段 catch { log.error } 的语义变更（务必注意）</h3>
 * 它原来表达"投递失败不影响用户发布"，现在**登记失败会回滚整个业务事务**，接口向用户报错。
 * 因为 record() 与业务写在同一个事务里 —— 这是"同一事务"这个保证的另一面，不是退步：
 *   - 登记失败 ⇒ 业务与消息无法原子提交 ⇒ 若只记日志继续提交，就等于承认"评价存在但下游永不感知"；
 *   - 现在的失败是**可见的**（用户收到 500，可以重发），而不是静默断裂；
 *   - 真正需要"失败不影响业务"的是**投递**环节，它发生在提交之后，异常已被 dispatch() 吞成
 *     FAILED + 重试，绝不会把用户请求打成失败。
 * 因此本类**不再持有 RabbitTemplate**：投递只允许走本地消息表，从结构上杜绝
 * "顺手在事务里直发一条事件"的写法再次出现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentEventPublisher {

    private final IMessageLogService messageLogService;

    /**
     * 发布"评价已产生"事件（路由键 review.created，事件体见 {@link ReviewCreatedEvent}）。
     *
     * <p><b>必须在业务事务内调用</b>（BlogServiceImpl.publishBlog 是 @Transactional）：
     * 登记要跟着业务一起提交；若在没有事务的地方被调用，record() 会自己开一个短事务先落库，
     * 之后再投递 —— 不会丢消息，但"业务成功 ⇔ 消息存在"的原子性就没有了。
     */
    public void publishReviewCreated(ReviewCreatedEvent event) {
        if (event == null || event.getBlogId() == null) {
            log.warn("忽略非法的 review.created 事件: {}", event);
            return;
        }
        // 幂等键 = 评价 id（= blogId）。调用方已经填了 bizId（见 BlogServiceImpl），
        // 这里只做兜底，保证"消息表的 biz_id"与"消费端 consume_record 的 biz_id"永远是同一个值：
        // 两边不一致的话，同一条评价在门店域看来就是不同的两条事件，热度/评价数会被重复累加。
        String bizId = event.getBizId() != null ? event.getBizId() : String.valueOf(event.getBlogId());
        event.setBizId(bizId);

        // ① 同事务落库（登记失败会回滚业务，见类注释）
        messageLogService.record(MqConstants.BIZ_REVIEW_CREATED, bizId,
                MqConstants.CONTENT_EXCHANGE, MqConstants.REVIEW_CREATED_KEY, event);
        // ② 提交后投递
        messageLogService.dispatchAfterCommit(MqConstants.BIZ_REVIEW_CREATED, bizId);
        log.info("已登记 review.created 事件（提交后投递）: bizId={}, shopId={}, score={}",
                bizId, event.getShopId(), event.getScore());
    }
}
