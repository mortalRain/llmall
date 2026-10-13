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
 * 本地消息表（门店域，审计 B4）。
 *
 * 与 llmall-trade 的同名表结构一致，但解决的是门店域自己的问题：
 * 「评价 → 门店热度 → 搜索索引」这一跳的发布方原来是"事务内 convertAndSend + 失败只 log.error"，
 * 于是 MQ 一抖动就变成"评价存在、热度不变"，而且**没有任何信号**（连重试都没有）。
 *
 * 做法：把"要发的事件"和业务数据（shop 行的热度/评分/销量更新）放在**同一个本地事务**里落库。
 *  - 事务提交 ⇒ 消息一定存在（不会丢）；
 *  - 提交后才投递（{@code dispatchAfterCommit}）：回滚了就不会发，杜绝"回滚了还发消息"；
 *  - 投递失败不影响业务，由 {@code MessageRetryTask} 按指数退避重发；
 *  - 下游消费幂等 + {@code ShopSearchReconcileTask} 定时对账，保证最终一致。
 *
 * 状态位里 {@link #STATUS_DONE} 在本域是有真实用途的：对账任务问搜索域
 * "这条事件你处理了吗"，回答"已处理"才把行置为 DONE —— 这样对账窗口不会无限重复检查同一批事件。
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
    /** 已完成（下游对账确认已处理） */
    public static final int STATUS_DONE = 2;
    /** 投递失败待重试（按 nextRetryTime 退避重发） */
    public static final int STATUS_FAILED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务类型，同时用于确定消息体的目标类型（本域为 SHOP_HOT_CHANGED） */
    private String bizType;

    /**
     * 业务主键：门店 id + 事件毫秒时间戳。
     *
     * 为什么不像交易域那样直接用业务单号：热度变更是**同一门店的多次事件**，
     * 用门店 id 当唯一键会把后续事件全部顶掉（uk_biz 会判重忽略）。
     * 加上毫秒时间戳后每次变更都是一条新消息，且这个 id 会被原样带给搜索域，
     * 成为消费记录（consume_record）的幂等键 —— 对账任务就是拿它去问下游"处理了没有"。
     */
    private String bizId;

    private String exchange;

    private String routingKey;

    /** 消息体（JSON）：整行门店快照，见 com.llmall.shop.event.ShopDocChangedEvent */
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
