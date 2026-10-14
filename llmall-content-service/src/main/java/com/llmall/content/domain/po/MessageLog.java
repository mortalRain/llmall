package com.llmall.content.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表（内容域生产端）。
 *
 * 事件与业务数据（blog 行 + feed_inbox）在同一个本地事务里落库，提交之后才投递，
 * 投递失败由 {@code MessageRetryTask} 按指数退避重发，失败原因/次数写回本表。
 *
 * 【注意】内容域不做对账，{@link #STATUS_DONE} 没有写入方，
 * 仅与门店域/交易域保持结构一致。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("message_log")
public class MessageLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待发送 */
    public static final int STATUS_PENDING = 0;
    /** 已投递（已进入 MQ，但下游是否处理完还不知道） */
    public static final int STATUS_SENT = 1;
    /** 已完成（下游对账确认已处理）—— 本域无对账任务，暂无写入方 */
    public static final int STATUS_DONE = 2;
    /** 投递失败待重试（按 nextRetryTime 退避重发） */
    public static final int STATUS_FAILED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务类型，同时用于确定消息体的目标类型（本域目前只有 REVIEW_CREATED） */
    private String bizType;

    /**
     * 业务主键：评价/笔记 id（= {@code ReviewCreatedEvent.bizId}）。
     *
     * 【注意】该值会被原样带进门店域 consume_record 的幂等键（biz_type=REVIEW_CREATED, biz_id,
     * consumer=llmall-shop-service），改成"blogId:时间戳"会让同一条评价在下游被当成两条事件、
     * 热度/评价数被重复累加且累加型错误不报错；一条评价只产生一条 review.created，无需时间戳。
     */
    private String bizId;

    private String exchange;

    private String routingKey;

    /**
     * 消息体（JSON）：ReviewCreatedEvent 的完整字段，见 com.llmall.api.event.ReviewCreatedEvent。
     *
     * 【注意】Long 字段会写成带引号的字符串，别以为数据坏了：llmall-common 的 JsonConfig 给
     * Long/BigInteger 全局注册了 ToStringSerializer（避免大整数在 JS 端丢精度），属全项目统一口径；
     * 投递时反序列化回 ReviewCreatedEvent 后类型即恢复为 Long。
     */
    private String payload;

    /** 0 待发送 1 已投递 2 已完成 3 失败待重试 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次重试时间（指数退避） */
    private LocalDateTime nextRetryTime;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
