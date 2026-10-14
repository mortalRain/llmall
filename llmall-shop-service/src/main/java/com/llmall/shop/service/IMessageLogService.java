package com.llmall.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.shop.domain.po.MessageLog;

import java.util.List;

/**
 * 本地消息服务（门店域）：登记、投递、重试、对账补偿。
 *
 * 与 llmall-trade 的 IMessageLogService 同一套口径，只多一个 {@link #markDone}。
 */
public interface IMessageLogService extends IService<MessageLog> {

    /** 【注意】必须与业务写操作处于同一事务，否则会出现业务成功而消息没落库 */
    void record(String bizType, String bizId, String exchange, String routingKey, Object payload);

    /** 事务提交后立即尝试投递；投递失败不影响主流程，留给定时任务重试 */
    void dispatchAfterCommit(String bizType, String bizId);

    /** 立即投递一条消息 */
    boolean dispatch(MessageLog messageLog);

    /** 查询待重试的消息（按下次重试时间排序） */
    List<MessageLog> listRetryable(int limit);

    /** 重试一批待发送消息，返回成功条数 */
    int retryPending(int limit);

    /** 对账补偿：把指定业务消息重新投递一次；下游以 consume_record 唯一键幂等，重复投递安全 */
    boolean compensate(String bizType, String bizId);

    /** 对账判据为"下游已处理"时调用：把消息置为完成态，避免对账窗口反复检查同一批事件 */
    void markDone(Long messageLogId);
}
