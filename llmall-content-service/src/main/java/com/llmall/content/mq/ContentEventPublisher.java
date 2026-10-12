package com.llmall.content.mq;

import com.llmall.api.event.ReviewCreatedEvent;
import com.llmall.common.constant.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 内容域事件发布。
 *
 * 注意这里只负责"投递"，不负责"投递可靠性"：
 * 真正的可靠投递（本地消息表 + 重试 + 对账补偿）在 M5 亮点 B 里统一实现。
 * 本轮先保证语义正确（提交后再投递），可靠性方案随后叠加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    /** 投递"评价已产生"事件，触发门店域更新热度 */
    public void publishReviewCreated(ReviewCreatedEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    MqConstants.CONTENT_EXCHANGE, MqConstants.REVIEW_CREATED_KEY, event);
            log.info("已投递 review.created 事件: blogId={}, shopId={}, score={}",
                    event.getBlogId(), event.getShopId(), event.getScore());
        } catch (Exception e) {
            // 投递失败不能影响用户发布（评价已经落库），但要告警并留待对账补偿
            log.error("投递 review.created 事件失败, event={}", event, e);
        }
    }
}
