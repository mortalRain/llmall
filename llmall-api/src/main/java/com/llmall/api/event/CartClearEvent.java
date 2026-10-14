package com.llmall.api.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 下单成功事件（llmall-trade-service 发布 → llmall-cart-service 消费，用于清理购物车中已下单的商品）。
 *
 * 【注意】必须随消息携带 userId：MQ 监听线程没有登录上下文（UserContext 是 ThreadLocal，
 * 只由网关请求线程写入），消费端若用 UserContext.getUser() 过滤会恒等于 eq(user_id, null)，
 * 购物车清不掉且静默失效。身份取自下单那一刻的登录态，由消息传递。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartClearEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 下单用户 id（下单时从 UserContext 取出，随消息传递） */
    private Long userId;

    /** 已下单的商品 id 列表 */
    private List<Long> itemIds;
}
