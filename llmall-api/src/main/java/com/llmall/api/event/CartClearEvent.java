package com.llmall.api.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 下单成功事件（llmall-trade-service 发布 → llmall-cart-service 消费，用于清理购物车中已下单的商品）。
 *
 * 为什么事件里必须带 userId：
 *   原来的实现只发"商品 id 集合"，消费端用 UserContext.getUser() 去过滤用户 ——
 *   但 MQ 监听线程里**没有登录上下文**（UserContext 是 ThreadLocal，由网关请求线程写入），
 *   于是 eq(user_id, null) 恒不匹配，购物车永远清不掉，而且不报错、只静默失效。
 *   把 userId 放进消息体，是"跨线程/跨服务传递身份"的正确做法：
 *   身份来自下单那一刻的登录态，由消息携带，而不是指望消费线程能读到 ThreadLocal。
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
