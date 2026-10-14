package com.llmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表（可靠消息最终一致性的核心）。
 *
 * 【注意】时序约束：消息必须与业务数据在同一个本地事务里落库，事务提交后再投递；
 * 投递失败不影响业务，由定时任务按退避策略重发，配合下游消费幂等与定时对账兜底达成最终一致。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("message_log")
public class MessageLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待发送 */
    public static final int STATUS_PENDING = 0;
    /** 已投递（已进入 MQ） */
    public static final int STATUS_SENT = 1;
    /** 已完成（已被消费方确认，可选） */
    public static final int STATUS_DONE = 2;
    /** 失败待人工/继续重试 */
    public static final int STATUS_FAILED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务类型，同时用于确定消息体的目标类型（如 ORDER_PAID） */
    private String bizType;

    /** 业务主键（订单号），与 bizType 组成唯一键，保证同一业务只登记一条消息 */
    private String bizId;

    private String exchange;

    private String routingKey;

    /** 消息体（JSON 字符串） */
    private String payload;

    /** 0 待发送 1 已投递 2 已完成 3 失败，取值见上方 STATUS_* 常量 */
    private Integer status;

    private Integer retryCount;

    /** 下次重试时间（指数退避） */
    private LocalDateTime nextRetryTime;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
