package com.llmall.search.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 对账应答：某条事件在本域（搜索域）的消费状态（GET /search/consume-status）。
 *
 * 调用方是 llmall-shop-service 的 ShopSearchReconcileTask（对账兜底，审计 B4）：
 * 它拿本地消息表里"已投递但尚未确认"的事件逐条来问"你处理了吗"，
 * 据此决定"置为完成 / 补发 / 下轮再查"。
 *
 * consumed 用包装类型 Boolean 是**刻意**的：字段缺失时能返回 null（"结论不可信"），
 * 让调用方按"未知"处理，而不是被基本类型的默认 false 骗成"明确未处理"从而补偿风暴。
 */
@Data
@Accessors(chain = true)
public class ConsumeStatusVO {

    private String bizType;

    private String bizId;

    /** true 已处理 / false 明确未处理 */
    private Boolean consumed;
}
