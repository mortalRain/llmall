package com.llmall.shop.client;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 对账应答：某条事件在搜索域的消费状态。
 *
 * 与 llmall-search-service 的 {@code com.llmall.search.domain.vo.ConsumeStatusVO} 字段一一对应
 * （本次改动不允许改 llmall-api，所以两边各持一份；JSON 字段名就是契约）。
 *
 * 【为什么 consumed 用包装类型 Boolean 而不是 boolean】
 * 反序列化时若对端少返回了字段（改字段名、返回了错误结构、被网关替换成别的响应体），
 * 基本类型会静默取默认值 false —— 对账任务会把它当成"**明确未处理**"从而触发补偿重发，
 * 下游一挂就变成补偿风暴。用 Boolean 时 null 表示"拿不到结论"，
 * 调用方按"未知"处理（不下结论、下轮再查），口径与 trade 的 OrderReconcileTask 一致。
 */
@Data
@Accessors(chain = true)
public class ConsumeStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String bizType;

    private String bizId;

    /** true 已处理 / false 明确未处理 / null 结果不可信（按未知处理） */
    private Boolean consumed;
}
