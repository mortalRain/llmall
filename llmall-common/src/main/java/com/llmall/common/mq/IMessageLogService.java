package com.llmall.common.mq;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 本地消息表的操作契约（可靠消息最终一致性的三段式）。
 *
 * 时序三段不可合并，任何一段缺失都会造成"业务成功但下游永远不知道"：
 * 1) {@link #record} 与业务写同一本地事务：业务成功 ⇒ 消息一定存在；
 * 2) {@link #dispatchAfterCommit} 在事务提交之后才投递：回滚了就不发；
 * 3) {@link #retryPending} 与 {@link #compensate} 兜住"当时投不出去"与"发出去了但下游没处理成功"。
 *
 * 各服务直接注入 {@code IMessageLogService<自己的 MessageLog>}，实现由 {@link AbstractMessageLogService} 提供。
 *
 * @param <M> 该服务自己的 message_log 持久化对象
 */
public interface IMessageLogService<M extends MessageLogSupport> extends IService<M> {

    /**
     * 登记一条待投递消息，必须与业务写在同一事务内调用。
     *
     * @param bizType    业务类型，同时是 payload 反序列化的类型键，新增类型必须在子类的 payloadTypes 登记
     * @param bizId      业务主键，与 bizType 组成唯一键，重复登记由唯一索引拦下
     * @param exchange   目标交换机
     * @param routingKey 路由键
     * @param payload    事件对象，落库前序列化为 JSON
     */
    void record(String bizType, String bizId, String exchange, String routingKey, Object payload);

    /** 注册"事务提交后投递"；无事务上下文（如运维接口直接调用）时立即投递。 */
    void dispatchAfterCommit(String bizType, String bizId);

    /** 立即投递一条消息；成功返回 true，失败则记失败状态与退避时间后返回 false。 */
    boolean dispatch(M messageLog);

    /** 查出到期待重试的消息（待发送 + 失败待重试）。 */
    List<M> listRetryable(int limit);

    /** 按退避策略重试一批到期消息，返回本轮成功条数。 */
    int retryPending(int limit);

    /** 对账补偿：按业务键找到原消息并重发。 */
    boolean compensate(String bizType, String bizId);

    /** 下游确认已处理后标记完成，使对账窗口不再重复检查该条消息。 */
    void markDone(Long messageLogId);
}
