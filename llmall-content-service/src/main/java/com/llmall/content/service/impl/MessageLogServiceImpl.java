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
 * 本地消息表实现（内容域，审计 B4 生产端）：把"发消息"变成"先落库、再投递、失败重试"。
 *
 * 时序分三段，各自解决一个问题（与门店域 MessageLogServiceImpl 同一套设计）：
 *  1. record()              —— 与业务同事务：保证"业务成功 ⇒ 消息一定存在"（不丢）
 *  2. dispatchAfterCommit() —— 提交后立刻投递：保证低延迟，且**回滚了就不发**
 *  3. retryPending()        —— 定时按指数退避重发：保证"当时投不出去，最终也能到"
 *
 * <h3>⚠️ 本轮刻意的语义变更（登记失败 = 业务回滚）</h3>
 * 改造前 {@code ContentEventPublisher} 的 {@code catch (Exception e) { log.error(...) }} 表达的是
 * "投递失败不影响用户发布"：评价已经落库，发不出去只记日志。
 * 现在这个 catch 被去掉了 —— 因为 record() 与业务写处在**同一个本地事务**里，
 * 登记失败（表不存在、连接被打断、字段超长……）会连同业务写一起回滚，接口向用户返回 500。
 * 这是**更正确**的行为，理由：登记失败意味着"业务与消息无法作为一个原子单位提交"，
 * 此时若只记日志继续提交，就精确地复现了审计 B4 要修的缺陷 ——
 * "评价存在、门店热度永不更新"，而且是静默的，事后无法从任何信号里恢复。
 * 宁可让用户看到失败并重试（重试是幂等的业务动作：只是再发一条评价），
 * 也不要产生一条永远无人感知的断裂。真正的"投递失败不阻断业务"发生在**提交之后**：
 * afterCommit 里的 convertAndSend 异常一律被 dispatch() 吞成 FAILED + 退避重试。
 *
 * <h3>与本域原有的 TxAfterCommit 的关系</h3>
 * 原来 {@code BlogServiceImpl.publishBlog} 用 {@code TxAfterCommit.run} 把"投递"挪到提交后。
 * 改造后**不能再套那层**：TxAfterCommit 会让 record() 也跑到提交之后（脱离业务事务，
 * 就又变回"业务成功但消息可能没落库"），而且会在 afterCommit 回调里再注册一次 afterCommit
 * （嵌套注册的同步器不会被执行）。现在"提交后才真正发出去"这件事由 dispatchAfterCommit 负责，
 * 语义不变，反而把"登记"拉回了事务内。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageLogServiceImpl extends ServiceImpl<MessageLogMapper, MessageLog> implements IMessageLogService {

    /** 重试退避基数（秒），下一次重试间隔 = base * 2^retryCount，上限 10 分钟 */
    private static final long RETRY_BASE_SECONDS = 5L;
    private static final long RETRY_MAX_SECONDS = 600L;

    /**
     * bizType → 消息体目标类型。
     * 为什么需要这个映射：本地消息表里存的是 JSON 字符串，投递时要**还原成原本的事件对象**，
     * 下游（门店域 ReviewCreatedListener 的 @RabbitListener 方法签名）才能按类型反序列化。
     * 直接把 payload 当 Map 发出去，消费端拿到的是 LinkedHashMap，字段语义全靠猜，且
     * __TypeId__ 头会指向 LinkedHashMap —— 表现为消费端反序列化/参数绑定失败。
     * 新增事件类型时必须在这里登记，否则投递阶段抛"未注册的消息类型"，被 catch 成失败重试。
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
            // 注意这里**捕获的是 DuplicateKeyException**：save() 是同类内部调用、不经过事务代理，
            // 不会被标记 rollback-only，所以吞掉它不会把外层业务事务弄脏。
            log.info("消息已登记过，忽略: bizType={}, bizId={}", bizType, bizId);
        }
    }

    /**
     * 事务提交后再投递。
     *
     * 为什么不能就地发（这是本方法存在的全部理由）：调用方（BlogServiceImpl.publishBlog）
     * 是在业务事务里登记消息的，如果顺手就地 convertAndSend，会出现两种都很难查的错：
     *  ① 事务随后回滚 → 事件已经发出去，门店域按"没发生过的评价"改了热度/评分；
     *  ② 提交之后进程崩溃 → 事件永远发不出去，业务却已经生效（"评价存在、热度不变"）。
     * 注册到 afterCommit 后，注册时点与提交时点之间的窗口由"消息已经和业务同事务落库"兜住：
     * 提交成功 ⇒ 消息一定在表里 ⇒ 即使这段窗口断电，MessageRetryTask 也会把它发出去。
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
                    // 在调用方看来是失败的（接口会返回 500，而 blog 行其实已经落库）。
                    // dispatch 内部把所有异常吞成日志 + FAILED 状态，这里不会抛。
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
            // 成功时把 error_msg 清回 NULL：MyBatis-Plus 全局配置是 update-strategy=not_null，
            // 但 LambdaUpdateWrapper.set(field, null) 是**显式** set，会照常生成 error_msg = null，
            // 所以"失败原因"不会被上一次重试的残留值污染（实测：MQ 恢复后重试成功，该列由报错文本变为 NULL）。
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
            // 改造前这里就是事件永久丢失的地方（只有一条 log.error，没有任何后续动作）
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
