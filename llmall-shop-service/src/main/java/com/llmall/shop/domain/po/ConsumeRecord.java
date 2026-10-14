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
 * 【注意】MQ 只保证 at-least-once，重复投递由 (biz_type, biz_id, consumer) 唯一索引拦截，
 * 业务表不为此加状态字段。
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
