package com.llmall.common.constant;

/**
 * 跨服务 MQ 契约统一收口。
 *
 * 为什么集中管理：交换机名、路由键、队列名是**生产者与消费者之间的契约**，
 * 两边各写一份字符串，改名时必然漏改一边，症状是"消息发出去了但没人消费"，且没有任何报错——
 * 排查成本极高。集中到共享库是微服务间契约治理的最低要求。
 *
 * 命名约定：<域>.topic 为交换机，<动作> 为路由键，<消费方>.<事件>.queue 为队列。
 */
public interface MqConstants {

    // ==================== 内容域 → 门店域：评价产生 ====================

    /** 内容域交换机（topic） */
    String CONTENT_EXCHANGE = "content.topic";

    /** 路由键：评价/笔记发布 */
    String REVIEW_CREATED_KEY = "review.created";

    /** 队列：门店热度更新（llmall-shop-service 消费） */
    String REVIEW_CREATED_QUEUE = "shop.hot.review.queue";

    // ==================== 门店域 → 搜索域：热度变更 ====================

    /** 门店域交换机（topic） */
    String SHOP_EXCHANGE = "shop.topic";

    /** 路由键：门店热度分变更 */
    String SHOP_HOT_CHANGED_KEY = "shop.hot.changed";

    /** 队列：搜索索引同步（llmall-search-service 消费，M6 接入） */
    String SHOP_HOT_CHANGED_QUEUE = "search.shop.hot.queue";

    // ==================== 交易域 → 用户域 / 门店域：订单支付成功 ====================

    /** 交易域交换机（topic），购物车清理也复用该交换机 */
    String TRADE_EXCHANGE = "llmall.trade.topic";

    /** 路由键：订单支付成功 */
    String ORDER_PAID_KEY = "order.paid";

    /** 队列：加积分（llmall-user-service 消费） */
    String ORDER_PAID_USER_QUEUE = "user.points.order.paid.queue";

    /** 队列：门店销量与热度（llmall-shop-service 消费） */
    String ORDER_PAID_SHOP_QUEUE = "shop.sold.order.paid.queue";

    // ==================== 交易域 → 门店域：到店核销完成 ====================

    /**
     * 路由键：订单核销完成（到店核销闭环的终点）。
     *
     * 为什么要单独发一个事件，而不是"改完状态就算了"：门店侧的钱是按核销结算的 ——
     * 只有"顾客真的到店用掉了"这笔订单，门店才应该拿到分成。因此核销是财务口径上的
     * "履约完成"信号，必须能从交易域传播出去，否则结算只能靠人去数据库捞 status=7。
     * 复用 llmall.trade.topic 而不新建交换机：核销与支付同属交易域的事实，同一个域的事件
     * 放在同一个交换机里，下游按路由键订阅，避免交换机数量随事件数量线性增长。
     */
    String ORDER_VERIFIED_KEY = "order.verified";

    /**
     * 队列：门店核销结算/对账（llmall-shop-service 后续消费）。
     *
     * 先声明队列再投递的原因：topic 交换机对"没有绑定队列的路由键"是**静默丢弃**的，
     * 如果这里不落一个队列，本期发出的核销事件会当场消失，留的"钩子"就是假的。
     * 队列先落消息、消费端后接，是这类"先有事实、后有账本"场景的常用做法。
     */
    String ORDER_VERIFIED_SETTLE_QUEUE = "shop.order.verified.settle.queue";

    // ==================== 交易域 → 购物车域：下单成功 ====================

    /** 路由键：下单成功（交易域发出，购物车域消费） */
    String ORDER_CREATE_KEY = "order.create";

    /** 队列：清空已下单的商品（llmall-cart-service 消费） */
    String CART_CLEAR_QUEUE = "llmall.cart.clear.queue";

    // ==================== 支付域 → 交易域：支付成功 ====================

    /** 支付域交换机（direct） */
    String PAY_EXCHANGE = "llmall.pay.direct";

    /** 路由键：支付成功 */
    String PAY_SUCCESS_KEY = "pay.success";

    /** 队列：标记订单已支付（llmall-trade-service 消费） */
    String PAY_SUCCESS_QUEUE = "llmall.trade.pay.success.queue";

    // ==================== 交易域内部：延迟消息（订单超时关单） ====================

    /** 延迟交换机（需要 RabbitMQ 的 delayed-message-exchange 插件） */
    String DELAY_EXCHANGE = "trade.delay.direct";

    /** 队列：延迟关单检查 */
    String DELAY_ORDER_QUEUE = "trade.delay.order.queue";

    /** 路由键：延迟关单检查 */
    String DELAY_ORDER_KEY = "delay.order.query";
    // ==================== 业务类型（幂等表 biz_type） ====================

    /** 评价产生事件 */
    String BIZ_REVIEW_CREATED = "REVIEW_CREATED";

    /** 门店热度变更事件 */
    String BIZ_SHOP_HOT_CHANGED = "SHOP_HOT_CHANGED";

    /** 订单支付成功事件 */
    String BIZ_ORDER_PAID = "ORDER_PAID";

    /** 下单扣减库存流水 */
    String BIZ_ORDER_CREATED = "ORDER_CREATED";

    /** 取消订单回滚库存流水 */
    String BIZ_ORDER_CANCELED = "ORDER_CANCELED";

    /** 订单核销成功事件 */
    String BIZ_ORDER_VERIFIED = "ORDER_VERIFIED";

    /**
     * 已支付订单退款回滚库存流水。
     *
     * 为什么不直接复用 BIZ_ORDER_CANCELED：库存流水的幂等键是 (biz_type, bizId, itemId)，
     * 用不同的业务号能让"关单回滚"与"退款回滚"在流水里可区分 —— 对账时一眼能看出
     * 这笔库存是因为没付款被释放的，还是因为退款的；也让"取消后又退款"这类
     * 异常叠加不会互相顶掉幂等记录。
     */
    String BIZ_ORDER_REFUNDED = "ORDER_REFUNDED";
}
