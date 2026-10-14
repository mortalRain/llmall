package com.llmall.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.event.ReviewCreatedEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.content.domain.po.MessageLog;
import com.llmall.content.mapper.MessageLogMapper;
import com.llmall.content.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地消息表实现（内容域生产端）：把"发消息"变成"先落库、再投递、失败重试"。
 *
 * 时序三段缺一不可：record() 与业务同事务落库（业务成功 ⇒ 消息一定存在）；
 * dispatchAfterCommit() 在事务提交之后才投递（回滚了就不发）；retryPending() 按指数退避重发。
 *
 * 【注意】record() 与业务写同事务，登记失败会连业务写一起回滚、接口返回 500 —— 这是刻意的，
 * 只记日志继续提交就会静默产生"评价存在、门店热度永不更新"；投递发生在提交之后，失败只记
 * FAILED + 退避时间，不阻断业务。
 * 调用方也不能再套 {@code TxAfterCommit}：那会把 record() 挪到提交后脱离业务事务，
 * 且在 afterCommit 回调里嵌套注册的同步器不会被执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageLogServiceImpl extends ServiceImpl<MessageLogMapper, MessageLog> implements IMessageLogService {

    /** 重试退避基数（秒），下一次重试间隔 = base * 2^retryCount，上限 10 分钟 */
    private static final long RETRY_BASE_SECONDS = 5L;
    private static final long RETRY_MAX_SECONDS = 600L;

    /**
     * bizType → 消息体目标类型：库里存的是 JSON 字符串，投递时必须还原成原本的事件对象，
     * 否则消费端拿到的是 LinkedHashMap、{@code __TypeId__} 也指向 LinkedHashMap 而绑定失败。
     * 新增事件类型必须在这里登记，否则投递阶段抛"未注册的消息类型"并被计为失败重试。
     */
    private static final Map<String, Class<?>> PAYLOAD_TYPES = new HashMap<>();

    static {
        PAYLOAD_TYPES.put(MqConstants.BIZ_REVIEW_CREATED, ReviewCreatedEvent.class);
    }

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(String bizType, String bizId, String exchange, String routingKey, Object payload) {
        MessageLog messageLog = new MessageLog()
                .setBizType(bizType)
                .setBizId(bizId)
                .setExchange(exchange)
                .setRoutingKey(routingKey)
                .setPayload(writeJson(payload))
                .setStatus(MessageLog.STATUS_PENDING)
                .setRetryCount(0)
                .setNextRetryTime(LocalDateTime.now());
        try {
            save(messageLog);
        } catch (DuplicateKeyException e) {
            // (biz_type, biz_id) 唯一索引：同一业务重复登记直接忽略，天然幂等。
            // save() 是同类内部调用、不经事务代理，捕获 DuplicateKeyException 不会把外层事务标记 rollback-only。
            log.info("消息已登记过，忽略: bizType={}, bizId={}", bizType, bizId);
        }
    }

    /**
     * 事务提交后再投递，这是本方法存在的全部理由：就地发会出现两种都很难查的错 ——
     * ① 事务随后回滚，事件却已经发出去；② 提交之后进程崩溃，事件永远发不出去而业务已经生效。
     * 注册到 afterCommit 后，注册时点与提交时点之间的窗口由"消息已经和业务同事务落库"兜住：
     * 提交成功 ⇒ 消息一定在表里 ⇒ 即使这段窗口断电，重试任务也会把它发出去。
     */
    @Override
    public void dispatchAfterCommit(String bizType, String bizId) {
        Runnable action = () -> {
            MessageLog messageLog = getByBiz(bizType, bizId);
            if (messageLog != null) {
                dispatch(messageLog);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // afterCommit 里抛异常会传播给"提交事务的调用方"，让一个已经提交成功的事务
                    // 在调用方看来是失败的；dispatch 内部把异常全部吞成日志 + FAILED，这里不会抛。
                    action.run();
                }
            });
        } else {
            // 没有事务上下文（例如以后加运维接口直接调用）：立即投递，语义与"已提交"一致
            action.run();
        }
    }

    @Override
    public boolean dispatch(MessageLog messageLog) {
        try {
            Object payload = readPayload(messageLog);
            rabbitTemplate.convertAndSend(messageLog.getExchange(), messageLog.getRoutingKey(), payload);
            // 成功时把 error_msg 清回 NULL：LambdaUpdateWrapper.set(field, null) 是显式 set，
            // 不受 MyBatis-Plus 全局 update-strategy=not_null 影响，失败原因不会被上一次重试的残留值污染。
            lambdaUpdate()
                    .eq(MessageLog::getId, messageLog.getId())
                    .set(MessageLog::getStatus, MessageLog.STATUS_SENT)
                    .set(MessageLog::getErrorMsg, null)
                    .update();
            log.info("消息投递成功: bizType={}, bizId={}", messageLog.getBizType(), messageLog.getBizId());
            return true;
        } catch (Exception e) {
            int retry = (messageLog.getRetryCount() == null ? 0 : messageLog.getRetryCount()) + 1;
            long delaySeconds = Math.min(RETRY_BASE_SECONDS * (1L << Math.min(retry, 10)), RETRY_MAX_SECONDS);
            lambdaUpdate()
                    .eq(MessageLog::getId, messageLog.getId())
                    .set(MessageLog::getStatus, MessageLog.STATUS_FAILED)
                    .set(MessageLog::getRetryCount, retry)
                    .set(MessageLog::getNextRetryTime, LocalDateTime.now().plusSeconds(delaySeconds))
                    .set(MessageLog::getErrorMsg, truncate(e.getMessage(), 1000))
                    .update();
            log.error("消息投递失败，将在 {} 秒后重试(第 {} 次): bizType={}, bizId={}",
                    delaySeconds, retry, messageLog.getBizType(), messageLog.getBizId(), e);
            return false;
        }
    }

    @Override
    public List<MessageLog> listRetryable(int limit) {
        return list(new LambdaQueryWrapper<MessageLog>()
                .in(MessageLog::getStatus, MessageLog.STATUS_PENDING, MessageLog.STATUS_FAILED)
                .le(MessageLog::getNextRetryTime, LocalDateTime.now())
                .orderByAsc(MessageLog::getNextRetryTime)
                .last("LIMIT " + limit));
    }

    @Override
    public int retryPending(int limit) {
        List<MessageLog> list = listRetryable(limit);
        int success = 0;
        for (MessageLog messageLog : list) {
            if (dispatch(messageLog)) {
                success++;
            }
        }
        return success;
    }

    private MessageLog getByBiz(String bizType, String bizId) {
        // 第二个参数 false：唯一键保证最多一条，不用抛 TooManyResultsException
        return getOne(new LambdaQueryWrapper<MessageLog>()
                .eq(MessageLog::getBizType, bizType)
                .eq(MessageLog::getBizId, bizId), false);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("消息体序列化失败", e);
        }
    }

    private Object readPayload(MessageLog messageLog) {
        Class<?> type = PAYLOAD_TYPES.get(messageLog.getBizType());
        if (type == null) {
            throw new IllegalStateException("未注册的消息类型: " + messageLog.getBizType());
        }
        try {
            return objectMapper.readValue(messageLog.getPayload(), type);
        } catch (Exception e) {
            throw new IllegalStateException("消息体反序列化失败: " + messageLog.getBizType(), e);
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
