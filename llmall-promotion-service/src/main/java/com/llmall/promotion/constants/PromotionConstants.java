package com.llmall.promotion.constants;

/**
 * 营销域内部常量（MQ 契约 / 业务类型）。
 *
 * 只放"本服务自己生产、自己消费"的常量；跨服务契约在 llmall-common 的 MqConstants，
 * 往里加一个只有本服务用的名字，只会让别的服务多维护一份无用常量。
 */
public interface PromotionConstants {

    /** 消费方标识，写进 consume_record.consumer */
    String CONSUMER_NAME = "llmall-promotion-service";

    /** 业务类型：秒杀落库（配合 consume_record 的唯一键 uk_consume 做消息级幂等） */
    String BIZ_SECKILL_ORDER = "SECKILL_ORDER";

    /**
     * 业务类型：付费券的购买退款。
     *
     * 付费券在抢购请求时就已扣款、落库却是异步的，落库失败必须退款，
     * 且同一条消息重投时不能退两次；借用 consume_record 的 uk_consume(biz_type, biz_id, consumer)
     * 表达"这件事我做过了"，无需新建表。
     */
    String BIZ_SECKILL_REFUND = "SECKILL_REFUND";
}
