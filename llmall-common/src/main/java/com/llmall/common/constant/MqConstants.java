package com.llmall.common.constant;

/**
 * 跨服务 MQ 契约统一收口：交换机、路由键、队列名与消息类型（biz_type）字典，
 * 命名约定为“域.topic” 交换机、“动作” 路由键、“消费方.事件.queue” 队列。
 *
 * 【注意】本地消息表唯一键是 (biz_type, biz_id)：新增消息类型必须登记对应的 biz_type 常量，
 * 复用同一 biz_type 发两条消息会被唯一键当成“已登记”而静默顶掉一条。
 */
public interface MqConstants {

    String CONTENT_EXCHANGE = "content.topic";

    String REVIEW_CREATED_KEY = "review.created";

    /** 队列：门店热度更新（llmall-shop-service 消费） */
    String REVIEW_CREATED_QUEUE = "shop.hot.review.queue";

    String SHOP_EXCHANGE = "shop.topic";

    String SHOP_HOT_CHANGED_KEY = "shop.hot.changed";

    /** 队列：搜索索引同步（llmall-search-service 消费） */
    String SHOP_HOT_CHANGED_QUEUE = "search.shop.hot.queue";

    /** 交易域交换机（topic），购物车清理也复用该交换机 */
    String TRADE_EXCHANGE = "llmall.trade.topic";

    String ORDER_PAID_KEY = "order.paid";

    /** 队列：加积分（llmall-user-service 消费） */
    String ORDER_PAID_USER_QUEUE = "user.points.order.paid.queue";

    /** 队列：门店销量与热度（llmall-shop-service 消费） */
    String ORDER_PAID_SHOP_QUEUE = "shop.sold.order.paid.queue";

    /**
     * 路由键：订单核销完成，门店按核销结算 —— 核销是财务口径上的“履约完成”信号。
     * 复用 llmall.trade.topic 而不新建交换机：同域事件共用一个交换机，下游按路由键订阅。
     */
    String ORDER_VERIFIED_KEY = "order.verified";

    /**
     * 队列：门店核销结算/对账（llmall-shop-service 后续消费）。
     * 【注意】topic 交换机对“没有绑定队列的路由键”是静默丢弃，必须先落队列再投递。
     */
    String ORDER_VERIFIED_SETTLE_QUEUE = "shop.order.verified.settle.queue";

    /** 路由键：下单成功（交易域发出，购物车域消费） */
    String ORDER_CREATE_KEY = "order.create";

    /** 队列：清空已下单的商品（llmall-cart-service 消费） */
    String CART_CLEAR_QUEUE = "llmall.cart.clear.queue";

    String PAY_EXCHANGE = "llmall.pay.direct";

    String PAY_SUCCESS_KEY = "pay.success";

    /** 队列：标记订单已支付（llmall-trade-service 消费） */
    String PAY_SUCCESS_QUEUE = "llmall.trade.pay.success.queue";

    /** 延迟交换机，需要 RabbitMQ 的 delayed-message-exchange 插件 */
    String DELAY_EXCHANGE = "trade.delay.direct";

    /** 队列：延迟关单检查 */
    String DELAY_ORDER_QUEUE = "trade.delay.order.queue";

    String DELAY_ORDER_KEY = "delay.order.query";

    /** 评价产生事件的 biz_type */
    String BIZ_REVIEW_CREATED = "REVIEW_CREATED";

    /** 门店热度变更事件的 biz_type */
    String BIZ_SHOP_HOT_CHANGED = "SHOP_HOT_CHANGED";

    /** 订单支付成功事件的 biz_type */
    String BIZ_ORDER_PAID = "ORDER_PAID";

    /** 下单扣减库存流水的 biz_type */
    String BIZ_ORDER_CREATED = "ORDER_CREATED";

    /**
     * 下单成功后清理购物车（下单链路里的第 4 步）：必须与 ORDER_CREATED 分属不同 biz_type，
     * 同一次下单的“清购物车”与“支付超时关单兜底”是两条消息。
     */
    String BIZ_CART_CLEAR = "CART_CLEAR";

    /** 取消订单回滚库存流水的 biz_type */
    String BIZ_ORDER_CANCELED = "ORDER_CANCELED";

    /** 订单核销成功事件的 biz_type */
    String BIZ_ORDER_VERIFIED = "ORDER_VERIFIED";

    /**
     * 已支付订单退款回滚库存流水。不复用 BIZ_ORDER_CANCELED：库存流水的幂等键是
     * (biz_type, bizId, itemId)，分开业务号才能区分“关单回滚”与“退款回滚”，
     * 且“取消后又退款”不会互相顶掉幂等记录。
     */
    String BIZ_ORDER_REFUNDED = "ORDER_REFUNDED";
    /**
     * 消费方标识：写入各自库 consume_record.consumer，也是对账查询的过滤条件。
     *
     * 【注意】必须与“对该事件负责的服务”严格对应：改名会让对账查询静默查不到已有消费记录，
     * 表现是同一批消息被反复补发，而日志里没有任何报错。
     */
    String CONSUMER_USER = "llmall-user-service";
    String CONSUMER_SHOP = "llmall-shop-service";
    String CONSUMER_SEARCH = "llmall-search-service";
    String CONSUMER_PROMOTION = "llmall-promotion-service";
}