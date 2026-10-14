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
 * 登记与投递分离：先 {@link IMessageLogService#record} 与业务写在同一个本地事务里落库，
 * 再由 {@link IMessageLogService#dispatchAfterCommit} 在提交之后才真正 convertAndSend；
 * 投递失败记成 FAILED + 退避时间，由 MessageRetryTask 定时重发，下游消费幂等（consume_record 唯一键）。
 *
 * 【注意】本类不再持有 RabbitTemplate —— 投递只允许走本地消息表，从结构上杜绝"顺手在事务里
 * 直发一条事件"的写法再次出现。同一事务的另一面是：登记失败会回滚整个业务事务并让接口报 500。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentEventPublisher {

    private final IMessageLogService messageLogService;

    /**
     * 发布"评价已产生"事件（路由键 review.created，事件体见 {@link ReviewCreatedEvent}）。
     *
     * 【注意】必须在业务事务内调用（BlogServiceImpl.publishBlog 是 @Transactional）：登记要跟着业务
     * 一起提交；若在没有事务的地方被调用，record() 会自己开一个短事务先落库，不会丢消息，
     * 但"业务成功 ⇔ 消息存在"的原子性就没有了。
     */
    public void publishReviewCreated(ReviewCreatedEvent event) {
        if (event == null || event.getBlogId() == null) {
            log.warn("忽略非法的 review.created 事件: {}", event);
            return;
        }
        // 幂等键 = 评价 id（= blogId）：调用方已填 bizId，这里只做兜底，保证"消息表的 biz_id"与
        // "消费端 consume_record 的 biz_id"永远是同一个值，否则同一条评价在下游会被重复累加热度/评价数。
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
