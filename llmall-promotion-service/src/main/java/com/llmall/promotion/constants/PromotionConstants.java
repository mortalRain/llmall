package com.llmall.promotion.constants;

/**
 * 营销域内部常量（MQ 契约 / 业务类型）。
 *
 * 这里只放"本服务自己生产、自己消费"的常量，所以不放进 llmall-common 的 MqConstants：
 * llmall-common 里那份是**跨服务契约**，多放一个只有 llmall-promotion-service 用的名字，
 * 只会让别的服务多一份需要一起维护的无用常量。
 */
public interface PromotionConstants {

    /** 消费方标识，写进 consume_record.consumer */
    String CONSUMER_NAME = "llmall-promotion-service";

    /** 业务类型：秒杀落库（配合 consume_record 的唯一键 uk_consume 做消息级幂等） */
    String BIZ_SECKILL_ORDER = "SECKILL_ORDER";

    /**
     * 业务类型：付费券的购买退款（Z3）。
     *
     * 为什么也借用 consume_record：付费券在"抢购请求"时就已经扣了钱，
     * 而落库是异步的 —— 落库失败必须把钱退回去，同时必须保证"M 一条消息重投时不会退两次"。
     * 退钱是不可逆的资金动作，恰好需要一张"这件事我做过了"的流水，
     * 而 consume_record 的 uk_consume(biz_type, biz_id, consumer) 就是这个语义，
     * 不需要为它新建任何表。
     */
    String BIZ_SECKILL_REFUND = "SECKILL_REFUND";
}
