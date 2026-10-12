package com.llmall.api.event;

import com.llmall.api.dto.OrderDetailDTO;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 事件：订单支付成功。
 *
 * 生产者：llmall-trade-service（**在本地事务内写入本地消息表**，提交后投递）
 * 消费者：llmall-user-service（加积分 + 记积分流水）、llmall-shop-service（累加门店销量 + 重算热度）
 *
 * 这是亮点 B 的载体：一次支付要触发多个下游动作，这些动作允许延迟、但**不能丢**，
 * 因此用"本地消息表 + MQ 最终一致性 + 消费幂等 + 定时对账补偿"来保证。
 */
@Data
@Accessors(chain = true)
public class OrderPaidEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键：订单号 */
    private String bizId;

    private Long orderId;

    private Long userId;

    /** 订单金额（分），积分按 100 分 = 1 积分折算 */
    private Integer totalFee;

    /** 1 实物订单 2 门店套餐/团购券订单 */
    private Integer orderType;

    /** 门店 id（团购券订单用于累加门店销量） */
    private Long shopId;

    /** 订单明细（itemId + num） */
    private List<OrderDetailDTO> items;

    /** 发生时间（epoch 毫秒） */
    private Long occurredAt;
}
