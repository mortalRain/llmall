package com.llmall.api.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 事件：用户发布了评价/探店笔记。
 *
 * 生产者：llmall-content-service（评价落库成功后投递）；消费者：llmall-shop-service
 * （更新门店评分/评价数/最近评价时间，并重算热度分）。
 *
 * 【注意】bizId 就是幂等键（等于 blogId）：MQ 只保证 at-least-once，消费端必须靠唯一键做幂等，
 * 否则同一条评价会被重复计入两次热度；时间字段用 epoch 毫秒，避免依赖双方的时间序列化配置。
 */
@Data
@Accessors(chain = true)
public class ReviewCreatedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键：评价 id */
    private String bizId;

    /** 评价/笔记 id */
    private Long blogId;

    /** 被评价的门店 id */
    private Long shopId;

    private Long userId;

    /** 评分 1~5（用于更新门店平均分） */
    private Integer score;

    /** 发生时间（epoch 毫秒） */
    private Long occurredAt;
}
