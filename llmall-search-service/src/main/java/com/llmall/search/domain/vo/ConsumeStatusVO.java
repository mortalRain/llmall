package com.llmall.search.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 对账应答：某条事件在搜索域的消费状态（GET /search/consume-status）。
 *
 * 调用方是 llmall-shop-service 的 ShopSearchReconcileTask，据此决定"置为完成 / 补发 / 下轮再查"。
 * 【注意】consumed 必须是包装类型 Boolean：字段缺失时返回 null 表示"结论不可信"，调用方按"未知"处理；
 * 若用基本类型会默认 false，被误判成"明确未处理"而引发补偿风暴。
 */
@Data
@Accessors(chain = true)
public class ConsumeStatusVO {

    private String bizType;

    private String bizId;

    /** true 已处理 / false 明确未处理 */
    private Boolean consumed;
}
