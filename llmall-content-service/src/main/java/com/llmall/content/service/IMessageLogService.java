package com.llmall.content.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.content.domain.po.MessageLog;

import java.util.List;

/**
 * 本地消息服务（内容域，审计 B4 生产端）：登记、提交后投递、退避重试。
 *
 * 与 llmall-shop / llmall-trade 的 IMessageLogService 同一套口径，本轮只实现"生产端三段"：
 *  1. {@link #record} —— 与业务写同事务落库（不丢）；
 *  2. {@link #dispatchAfterCommit} —— 提交之后才真正投递（不早发、不回滚了还发）；
 *  3. {@link #listRetryable}/{@link #retryPending} —— 投递失败按指数退避重发（不静默断裂）。
 *
 * 本域**没有** compensate/markDone：那两个方法的判据来自"问下游是否处理过"的对账任务
 * （下游 consume_record 三态判定），而内容域本轮不实现对账，故不写用不到的接口。
 */
public interface IMessageLogService extends IService<MessageLog> {

    /**
     * 在当前事务内登记一条待投递消息。
     * 必须与业务写操作处于同一事务：否则"业务成功但消息没落库"就退化回丢消息的老问题。
     * 登记失败会向上抛出并**回滚业务事务**（见实现类注释：这是本轮刻意的语义变更）。
     */
    void record(String bizType, String bizId, String exchange, String routingKey, Object payload);

    /** 事务提交后立即尝试投递；投递失败不影响主流程（记 FAILED + 退避时间），留给定时任务重试 */
    void dispatchAfterCommit(String bizType, String bizId);

    /** 立即投递一条消息 */
    boolean dispatch(MessageLog messageLog);

    /** 查询待重试的消息（按下次重试时间排序） */
    List<MessageLog> listRetryable(int limit);

    /** 重试一批待发送消息，返回成功条数 */
    int retryPending(int limit);
}
