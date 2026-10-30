package com.llmall.common.mq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.common.tx.TxAfterCommit;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 本地消息表的公共实现：把"发消息"变成"先落库、再投递、失败重试、对账兜底"。
 *
 * 【注意】时序分三段，不可合并：
 * 1) {@code record()} 与业务同事务：业务成功 ⇒ 消息一定存在（不丢）；
 * 2) {@code dispatchAfterCommit()} 必须在事务提交后投递，回滚了就不发；
 * 3) {@code retryPending()/compensate()} 定时重试 + 对账补偿：当时投不出去，最终也能到。
 *
 * 各服务只需提供自己的持久化对象与消息类型登记（见子类），表结构须一致：
 * 本类统一按列名读写，因为三张 message_log 的列名与类型完全相同（见 sql/merged 下的建表脚本）。
 *
 * 【注意】依赖 {@code @EnableScheduling}：漏加注解时子类的重试任务不会执行且没有任何报错，
 * 只能靠 message_log 里 status=3 的行堆积察觉。
 *
 * @param <M>  该服务的 message_log 持久化对象
 * @param <MP> 该服务的 Mapper
 */
@Slf4j
public abstract class AbstractMessageLogService<M extends MessageLogSupport, MP extends BaseMapper<M>>
        extends ServiceImpl<MP, M> implements IMessageLogService<M> {

    /** 重试退避基数（秒），下一次重试间隔 = base * 2^retryCount */
    private static final long RETRY_BASE_SECONDS = 5L;

    /** 退避上限（秒）：10 分钟 */
    private static final long RETRY_MAX_SECONDS = 600L;

    /** 退避指数的封顶，避免 retryCount 很大时移位溢出 */
    private static final int RETRY_SHIFT_CAP = 10;

    /** error_msg 落库前的截断长度，与 DDL 的 VARCHAR(1024) 对齐 */
    private static final int ERROR_MSG_MAX_LENGTH = 1000;

    /** message_log 的列名：三张表结构一致，故在基类统一引用 */
    private static final String COL_ID = "id";
    private static final String COL_BIZ_TYPE = "biz_type";
    private static final String COL_BIZ_ID = "biz_id";
    private static final String COL_STATUS = "status";
    private static final String COL_RETRY_COUNT = "retry_count";
    private static final String COL_NEXT_RETRY_TIME = "next_retry_time";
    private static final String COL_ERROR_MSG = "error_msg";

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    @Qualifier("outboxDispatchExecutor")
    private Executor dispatchExecutor;
    @Value("${llmall.mq.confirm-timeout-ms:5000}")
    private long confirmTimeoutMillis = 5000;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    protected AbstractMessageLogService(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /** 创建一条空的持久化对象。 */
    protected abstract M newMessageLog();

    /**
     * bizType → 消息体目标类型。
     *
     * 【注意】本地消息表里存的是 JSON 字符串，投递时必须还原成原本的事件对象，下游消息转换器才能按
     * "字段名 + 类型"正确反序列化（把 payload 当 Map 发出去，消费端拿到的是 LinkedHashMap）。
     * 新增事件类型必须登记，否则投递阶段直接抛"未注册的消息类型"、被 catch 成失败重试，
     * 表现是消息永远发不出去。
     */
    protected abstract Map<String, Class<?>> payloadTypes();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(String bizType, String bizId, String exchange, String routingKey, Object payload) {
        M messageLog = newMessageLog();
        messageLog.setBizType(bizType)
                .setBizId(bizId)
                .setExchange(exchange)
                .setRoutingKey(routingKey)
                .setPayload(writeJson(payload))
                .setStatus(MessageLogSupport.STATUS_PENDING)
                .setRetryCount(0)
                .setNextRetryTime(LocalDateTime.now());
        try {
            save(messageLog);
        } catch (DuplicateKeyException e) {
            // (biz_type, biz_id) 唯一索引：同一业务重复登记直接忽略，天然幂等
            log.info("消息已登记过，忽略: bizType={}, bizId={}", bizType, bizId);
        }
    }

    /**
     * 事务提交后再投递。
     *
     * 【注意】必须在业务事务内调用：就地发会出现"事务回滚了事件却已发出"与"提交后进程崩溃、事件永远
     * 发不出去"两种都很难查的错。注册到 afterCommit 后，注册时点与提交时点之间的窗口由"消息已经和业务
     * 同事务落库"兜住：提交成功 ⇒ 消息一定在表里 ⇒ 即使这段窗口断电，重试任务也会把它发出去。
     */
    @Override
    public void dispatchAfterCommit(String bizType, String bizId) {
        TxAfterCommit.run("enqueue " + bizType + ":" + bizId, () -> {
            try {
                dispatchExecutor.execute(() -> {
                    try {
                        M row = independently(() -> getByBiz(bizType, bizId));
                        if (row != null) dispatch(row);
                    } catch (RuntimeException failure) {
                        log.warn("消息后台投递暂不可用，持久待办将重试: type={}, id={}", bizType, bizId, failure);
                    }
                });
            } catch (RuntimeException saturated) {
                log.warn("消息投递队列繁忙，保留持久待办: type={}, id={}", bizType, bizId);
            }
        });
    }

    @Override
    public boolean dispatch(M messageLog) {
        return dispatch(messageLog, false);
    }

    private boolean dispatch(M messageLog, boolean force) {
        if (messageLog == null || messageLog.getId() == null) return false;
        try {
            M row = independently(() -> getById(messageLog.getId()));
            if (row == null) return false;
            if (Integer.valueOf(MessageLogSupport.STATUS_DONE).equals(row.getStatus())
                    || (!force && Integer.valueOf(MessageLogSupport.STATUS_SENT).equals(row.getStatus()))) return true;
            Object payload = readPayload(row);
            transaction(TransactionDefinition.PROPAGATION_NOT_SUPPORTED, () -> {
                ConfirmedMessagePublisher.convertAndSend(rabbitTemplate, row.getExchange(), row.getRoutingKey(), payload, confirmTimeoutMillis);
                return null;
            });
            saveDeliveryState(row.getId(), true, null);
            return true;
        } catch (Exception failure) {
            try { saveDeliveryState(messageLog.getId(), false, failure.getMessage()); }
            catch (RuntimeException unavailable) { log.error("投递状态未能落库，保留原状态待重试: id={}", messageLog.getId(), unavailable); }
            log.warn("消息未确认，保留重试: type={}, id={}", messageLog.getBizType(), messageLog.getBizId(), failure);
            return false;
        }
    }

    private void saveDeliveryState(Long id, boolean confirmed, String error) {
        independently(() -> {
            M row = getOne(new QueryWrapper<M>().eq(COL_ID, id).last("FOR UPDATE"), false);
            if (row == null || Integer.valueOf(MessageLogSupport.STATUS_DONE).equals(row.getStatus())) return null;
            // A late failed publisher must not erase a peer's acknowledged result.
            if (!confirmed && Integer.valueOf(MessageLogSupport.STATUS_SENT).equals(row.getStatus())) return null;
            UpdateWrapper<M> change = new UpdateWrapper<M>().eq(COL_ID, id);
            if (confirmed) {
                change.set(COL_STATUS, MessageLogSupport.STATUS_SENT).set(COL_ERROR_MSG, null);
            } else {
                int retry = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, row.getRetryCount() == null ? 0L : row.getRetryCount().longValue()) + 1L);
                long delay = Math.min(RETRY_BASE_SECONDS * (1L << Math.min(retry, RETRY_SHIFT_CAP)), RETRY_MAX_SECONDS);
                change.set(COL_STATUS, MessageLogSupport.STATUS_FAILED).set(COL_RETRY_COUNT, retry)
                        .set(COL_NEXT_RETRY_TIME, LocalDateTime.now().plusSeconds(delay)).set(COL_ERROR_MSG, truncate(error, ERROR_MSG_MAX_LENGTH));
            }
            update(change);
            return null;
        });
    }

    private <T> T independently(Supplier<T> action) {
        return transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW, action);
    }

    private <T> T transaction(int propagation, Supplier<T> action) {
        if (transactionManager == null) throw new IllegalStateException("Missing message transaction manager");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        return template.execute(status -> action.get());
    }

    @Override
    public List<M> listRetryable(int limit) {
        return list(new QueryWrapper<M>()
                .in(COL_STATUS, MessageLogSupport.STATUS_PENDING, MessageLogSupport.STATUS_FAILED)
                .le(COL_NEXT_RETRY_TIME, LocalDateTime.now())
                .orderByAsc(COL_NEXT_RETRY_TIME)
                .last("LIMIT " + limit));
    }

    @Override
    public int retryPending(int limit) {
        List<M> list = listRetryable(limit);
        int success = 0;
        for (M messageLog : list) {
            if (dispatch(messageLog)) {
                success++;
            }
        }
        return success;
    }

    @Override
    public boolean compensate(String bizType, String bizId) {
        M messageLog = getByBiz(bizType, bizId);
        if (messageLog == null) {
            log.warn("补偿失败：找不到消息记录 bizType={}, bizId={}", bizType, bizId);
            return false;
        }
        log.warn("触发对账补偿重发: bizType={}, bizId={}", bizType, bizId);
        return dispatch(messageLog, true);
    }

    @Override
    public void markDone(Long messageLogId) {
        if (messageLogId == null) {
            return;
        }
        update(new UpdateWrapper<M>()
                .eq(COL_ID, messageLogId)
                .set(COL_STATUS, MessageLogSupport.STATUS_DONE));
    }

    private M getByBiz(String bizType, String bizId) {
        return getOne(new QueryWrapper<M>()
                .eq(COL_BIZ_TYPE, bizType)
                .eq(COL_BIZ_ID, bizId), false);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("消息体序列化失败", e);
        }
    }

    private Object readPayload(M messageLog) {
        Class<?> type = payloadTypes().get(messageLog.getBizType());
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
