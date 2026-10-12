package com.llmall.shop.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息消费幂等记录。
 *
 * MQ 只保证 at-least-once：网络抖动、消费者重启、ack 丢失都会导致同一条消息被投递多次。
 * 靠 (biz_type, biz_id, consumer) 唯一索引做闸门，是消费端幂等最省事也最可靠的做法
 * （比"在业务表上加状态字段"更通用，业务表不用为了幂等而变形）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("consume_record")
public class ConsumeRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String bizType;

    private String bizId;

    /** 消费方标识（服务名），同一事件可能被多个服务各自消费 */
    private String consumer;

    private LocalDateTime createTime;
}
