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
 * 解决什么问题：如果"业务提交成功"和"消息投递成功"是两件独立的事，
 * 那么进程崩溃、网络抖动、MQ 不可用都会造成**业务成功但消息丢失**，下游永远不知道要处理。
 *
 * 做法：把"要发的消息"和业务数据放在**同一个本地事务**里落库。
 *  - 事务提交 → 消息一定存在（不会丢）；
 *  - 提交后再投递；投递失败也不影响业务，由定时任务按退避策略重发；
 *  - 下游消费幂等 + 定时对账兜底，保证最终一致。
 * 这就是"可靠消息最终一致性"，比引入强一致分布式事务更轻、更稳。
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

    /** 0 待发送 1 已投递 2 已完成 3 失败 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次重试时间（指数退避） */
    private LocalDateTime nextRetryTime;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
