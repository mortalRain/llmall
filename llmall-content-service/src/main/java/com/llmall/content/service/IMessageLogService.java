package com.llmall.content.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.content.domain.po.MessageLog;

import java.util.List;

/**
 * 本地消息服务（内容域生产端）：登记、提交后投递、退避重试。
 *
 * 与 llmall-shop / llmall-trade 的 IMessageLogService 同一套口径；本域不实现对账，故不提供
 * compensate/markDone（那两个方法的判据来自下游 consume_record 的三态对账）。
 */
public interface IMessageLogService extends IService<MessageLog> {

    /** 在当前事务内登记一条待投递消息；必须与业务写同事务，登记失败向上抛出并回滚业务事务 */
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
