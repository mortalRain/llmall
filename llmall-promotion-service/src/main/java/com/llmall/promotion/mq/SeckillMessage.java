package com.llmall.promotion.mq;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 秒杀落库消息体。
 *
 * 只带三个字段，因为消费者只需要它们就能完成落库：
 *   token     —— 幂等键（写进 consume_record.biz_id，同一条消息重复投递只落库一次）
 *   userId    —— 券的持有人，由生产者在鉴权之后写入，**不依赖 ThreadLocal**
 *                （MQ 消费线程里没有请求上下文，UserContext 一定是空的，这是异步化最容易踩的坑）
 *   voucherId —— 券 id
 * 门店、有效期等派生信息由消费者回查 voucher 得到：消息体越小越不容易出现"发出去之后再改字段"的兼容问题。
 */
@Data
@Accessors(chain = true)
public class SeckillMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 秒杀凭证：全局唯一，充当幂等键 */
    private String token;

    /** 抢购用户 id（生产者在请求线程里取出后显式带上） */
    private Long userId;

    /** 券 id */
    private Long voucherId;
}
