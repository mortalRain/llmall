package com.llmall.promotion.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息消费幂等记录，对应 `llmall-promotion`.`consume_record`。
 *
 * 唯一键 uk_consume(biz_type, biz_id, consumer) 是本模块**消息级幂等**的闸门，也是三个闸门里的最后一道：
 *   第一道  Redis 已购集合 SISMEMBER —— 挡住并发洪峰，成本最低；
 *   第二道  user_voucher.uk_user_voucher  —— 业务语义上"一人一张"的硬约束；
 *   第三道  consume_record.uk_consume      —— 同一条 MQ 消息（同一 token）只允许落库一次。
 * 第三道不可省：MQ 是 at-least-once，用户手动重试/网络抖动都会导致重投，
 * 而"插入已存在的 user_voucher"撞唯一键属于业务失败（会被当成"已持有"），
 * 无法区分"自己上一次已经成功"和"用户真的持有"，所以需要一张独立的消费流水来记账。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("consume_record")
public class ConsumeRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务类型，如 SECKILL_ORDER */
    private String bizType;

    /** 业务 id：秒杀消息里就是那次抢购的 token（天然全局唯一） */
    private String bizId;

    /** 消费方标识：同一个事件被多个服务消费时，各自维护自己的记录，互不干扰 */
    private String consumer;

    private LocalDateTime createTime;
}
