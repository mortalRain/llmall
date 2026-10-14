package com.llmall.api.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 事件：门店热度分发生变化。
 *
 * 生产者：llmall-shop-service（重算热度后投递）；
 * 消费者：llmall-search-service（更新 ES 文档的 hotScore 字段，供 function_score 综合排序）。
 */
@Data
@Accessors(chain = true)
public class ShopHotChangedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键：门店 id + 版本号（同一次热度变更只处理一次） */
    private String bizId;

    private Long shopId;

    private Long typeId;

    private Double hotScore;

    /** 评价数 */
    private Integer comments;

    /** 评分 ×10 */
    private Integer score;

    /** 发生时间（epoch 毫秒） */
    private Long occurredAt;
}
