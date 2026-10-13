package com.llmall.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.common.constant.MqConstants;
import com.llmall.shop.domain.po.MessageLog;
import com.llmall.shop.event.ShopDocChangedEvent;
import com.llmall.shop.mapper.MessageLogMapper;
import com.llmall.shop.service.IMessageLogService;
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
 * 本地消息表实现（门店域，审计 B4）：把"发消息"变成"先落库、再投递、失败重试、对账兜底"。
 *
 * 时序上分三段，各自解决一个问题（与 llmall-trade 的实现同一套设计）：
 *  1. record()             —— 与业务同事务：保证"业务成功 ⇒ 消息一定存在"（不丢）
 *  2. dispatchAfterCommit() —— 提交后立刻投递：保证低延迟，且**回滚了就不发**
 *  3. retryPending()/compensate() —— 定时重试 + 对账补偿：保证"当时投不出去，最终也能到"
 *
 * 这一段是审计 B4 的核心修复：原来的 ShopEventPublisher 直接 convertAndSend + catch{log.error}，
 * 既没有"提交后才发"的时序保证，也没有任何重试/对账 —— MQ 抖一下就永久断裂。
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
     * 为什么需要这个映射：本地消息表里存的是 JSON 字符串，投递时要还原成原本的事件对象，
     * 下游的消息转换器才能按"字段名 + 类型"正确反序列化（把 payload 当 Map 发出去，
     * 消费端拿到的是 LinkedHashMap，字段语义全靠猜）。新增事件类型时必须在这里登记，
     * 否则投递阶段会直接抛"未注册的消息类型"，被 catch 成失败重试 —— 表现是消息永远发不出去。
     */
    private static final Map<String, Class<?>> PAYLOAD_TYPES = new HashMap<>();

    static {
        PAYLOAD_TYPES.put(MqConstants.BIZ_SHOP_HOT_CHANGED, ShopDocChangedEvent.class);
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
            // (biz_type, biz_id) 唯一索引：同一业务重复登记直接忽略，天然幂等
            log.info("消息已登记过，忽略: bizType={}, bizId={}", bizType, bizId);
        }
    }

    /**
     * 事务提交后再投递。
     *
     * 为什么不能就地发：本方法一定是在业务事务里被调用的（评价消费/订单支付消费都会改 shop 行），
     * 就地发会出现两种都很难查的错：
     *  ① 事务随后回滚 → 事件已经发出去了，下游按"没发生过的变更"改了搜索视图；
     *  ② 提交之后进程崩溃 → 事件永远发不出去，业务却已经生效（"评价存在、热度不变"）。
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
                    // 在调用方看来是失败的（MQ 消费方可能因此重投整条 review.created）。
                    // dispatch 内部已经把所有异常吞成日志 + 失败状态，这里不会再抛。
                    action.run();
                }
            });
        } else {
            // 没有事务上下文（例如运维接口直接调用）：立即投递
            action.run();
        }
    }

    @Override
    public boolean dispatch(MessageLog messageLog) {
        try {
            Object payload = readPayload(messageLog);
            rabbitTemplate.convertAndSend(messageLog.getExchange(), messageLog.getRoutingKey(), payload);
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
            // 这里原来是事件永久丢失的地方（只有 log.error，没有任何后续动作）
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

    @Override
    public boolean compensate(String bizType, String bizId) {
        MessageLog messageLog = getByBiz(bizType, bizId);
        if (messageLog == null) {
            log.warn("补偿失败：找不到消息记录 bizType={}, bizId={}", bizType, bizId);
            return false;
        }
        log.warn("触发对账补偿重发: bizType={}, bizId={}", bizType, bizId);
        return dispatch(messageLog);
    }

    @Override
    public void markDone(Long messageLogId) {
        if (messageLogId == null) {
            return;
        }
        lambdaUpdate()
                .eq(MessageLog::getId, messageLogId)
                .set(MessageLog::getStatus, MessageLog.STATUS_DONE)
                .update();
    }

    private MessageLog getByBiz(String bizType, String bizId) {
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
