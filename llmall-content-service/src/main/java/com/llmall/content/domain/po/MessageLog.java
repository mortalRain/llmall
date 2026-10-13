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
 * 本地消息表（内容域，审计 B4 —— 生产端）。
 *
 * <h3>为什么内容域也需要这张表</h3>
 * 「评价发布 → review.created → 门店热度」这一跳的发布方原来是
 * {@code ContentEventPublisher#publishReviewCreated}：事务提交后用
 * {@code TxAfterCommit} 调用 {@code rabbitTemplate.convertAndSend}，失败只 {@code log.error}。
 * 时序是对的（提交后才发，不会"回滚了还发消息"），但**投递失败就等于事件永久消失**：
 * 提交成功之后、回调执行之前进程崩溃 / MQ 抖动 / 网络超时 —— 事件都不会再出现，
 * 而业务（blog 行）已经生效。表现是"评价存在、门店热度不变"，且没有任何信号（连重试都没有）。
 *
 * <h3>做法（与 llmall-shop / llmall-trade 的同名表同一套范式）</h3>
 * 把"要发的事件"和业务数据（blog 行 + feed_inbox）放进**同一个本地事务**落库：
 *  - 事务提交 ⇒ 消息一定存在（不会丢）；
 *  - 提交后才投递（{@code dispatchAfterCommit}）：仍然是"提交后才真正发出去"，回滚了就不发；
 *  - 投递失败不影响业务，由 {@code MessageRetryTask} 按指数退避重发，失败原因/次数写回本表；
 *  - 内容域本轮**不做对账**（审计 B4 只要求生产端的前三段：同事务登记 → 提交后投递 → 退避重试），
 *    所以 {@link #STATUS_DONE} 目前没有写入方，预留给出"问下游是否处理过"的定时对账
 *    （门店域的实现见 shop 的 ShopSearchReconcileTask，判据是下游 consume_record 里的幂等记录）。
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
    /** 已完成（下游对账确认已处理）—— 内容域本轮无对账任务，暂无人写入，结构上先与门店域/交易域保持一致 */
    public static final int STATUS_DONE = 2;
    /** 投递失败待重试（按 nextRetryTime 退避重发） */
    public static final int STATUS_FAILED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务类型，同时用于确定消息体的目标类型（本域目前只有 REVIEW_CREATED） */
    private String bizType;

    /**
     * 业务主键：**评价/笔记 id**（= {@code ReviewCreatedEvent.bizId}）。
     *
     * 为什么不学门店域那样加时间戳后缀：内容域"一条评价只产生一条 review.created"，
     * blogId 本身就是天然唯一的（blog 行只 insert 一次，不存在同一主键的多次变更事件）。
     * 更重要的是这个值会被原样带进门店域的 consume_record 幂等键
     * （biz_type=REVIEW_CREATED, biz_id, consumer=llmall-shop-service，见 ReviewCreatedListener）——
     * 改成"blogId:时间戳"会让同一条评价在下游被当成两条不同事件，**热度/评价数被重复累加**，
     * 而且累加型操作错了也不报错，只能靠这个键兜住。
     */
    private String bizId;

    private String exchange;

    private String routingKey;

    /**
     * 消息体（JSON）：ReviewCreatedEvent 的完整字段，见 com.llmall.api.event.ReviewCreatedEvent。
     *
     * ⚠️ 看这列时别以为数据坏了：Long 字段会写成**带引号的字符串**
     * （实测入库样例 {@code {"bizId":"920002","blogId":"920002","shopId":"1","userId":"1","score":5,"occurredAt":"1791888729425"}}），
     * 因为 llmall-common 的 JsonConfig 给 Long/BigInteger 全局注册了 ToStringSerializer
     * （避免大整数在 JS 端丢精度）。这是全项目 JSON 的统一口径，投递时反序列化回 ReviewCreatedEvent
     * 后类型恢复为 Long，消息体与改造前 direct convertAndSend 时完全一致（已实测门店域消费成功）。
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
