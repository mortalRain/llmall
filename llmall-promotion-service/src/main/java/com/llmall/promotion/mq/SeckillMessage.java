package com.llmall.promotion.mq;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 秒杀落库消息体：只带消费者落库所需的三个字段，门店、有效期等派生信息由消费者回查 voucher 得到。
 *
 * 【注意】userId 必须由生产者在鉴权之后显式带上：MQ 消费线程没有请求上下文，UserContext 一定为空。
 */
@Data
@Accessors(chain = true)
public class SeckillMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 秒杀凭证：全局唯一，充当幂等键（写进 consume_record.biz_id，重投只落库一次） */
    private String token;

    private Long userId;

    private Long voucherId;
}
