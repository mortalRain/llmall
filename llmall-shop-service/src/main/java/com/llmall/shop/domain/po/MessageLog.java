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
 * 门店域本地消息表：把"要发的事件"与业务数据（shop 行的热度/评分/销量）放在同一个本地事务里落库。
 *
 * 【注意】时序与状态约束：事务提交 ⇒ 消息一定存在（不会丢）；提交后才投递
 * （{@code dispatchAfterCommit}），回滚了就不会发；投递失败不影响业务，由 {@code MessageRetryTask}
 * 按指数退避重发，下游消费幂等 + {@code ShopSearchReconcileTask} 定时对账，保证最终一致。
 * {@link #STATUS_DONE} 只在搜索域回复"已处理"后写入，使对账窗口不会无限重复检查同一批事件。
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
     * 【注意】不能只用门店 id：热度变更是同一门店的多次事件，uk_biz 会把后续事件全部判重忽略。
     * 该值会原样带给搜索域，作为消费记录（consume_record）的幂等键 —— 对账任务就是拿它去问下游
     * "处理了没有"。
     */
    private String bizId;

    private String exchange;

    private String routingKey;

    /** 消息体（JSON）：整行门店快照，见 com.llmall.shop.event.ShopDocChangedEvent */
    private String payload;

    /** 0 待发送 1 已投递 2 已完成 3 失败待重试 */
    private Integer status;

    private Integer retryCount;

    /** 下次重试时间（指数退避） */
    private LocalDateTime nextRetryTime;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
