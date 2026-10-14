package com.llmall.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.trade.domain.po.MessageLog;

import java.util.List;

/**
 * 本地消息服务：登记、投递、重试、对账补偿。
 */
public interface IMessageLogService extends IService<MessageLog> {

    /**
     * 在当前事务内登记一条待投递消息。
     * 【注意】必须与业务写操作处于同一事务，否则"业务成功但消息没落库"，又回到丢消息的老问题。
     */
    void record(String bizType, String bizId, String exchange, String routingKey, Object payload);

    /** 事务提交后立即尝试投递；投递失败不影响主流程，留给定时任务重试 */
    void dispatchAfterCommit(String bizType, String bizId);

    /** 立即投递一条消息 */
    boolean dispatch(MessageLog messageLog);

    /** 查询待重试的消息（按下次重试时间排序） */
    List<MessageLog> listRetryable(int limit);

    /** 重试一批待发送消息，返回成功条数 */
    int retryPending(int limit);

    /**
     * 对账补偿：对指定业务的消息立即重新投递（用于"下游说我没收到"的场景）。
     * 【注意】重复投递是安全的，前提是下游消费幂等。
     */
    boolean compensate(String bizType, String bizId);
}
