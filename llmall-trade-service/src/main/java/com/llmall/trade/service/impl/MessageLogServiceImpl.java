package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.event.CartClearEvent;
import com.llmall.api.event.OrderPaidEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.trade.domain.po.MessageLog;
import com.llmall.trade.mapper.MessageLogMapper;
import com.llmall.trade.service.IMessageLogService;
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
 * 本地消息表实现：把"发消息"变成"先落库、再投递、失败重试、对账兜底"。
 *
 * 时序上分三段，各自解决一个问题：
 *  1. record()  —— 与业务同事务：保证"业务成功 ⇒ 消息一定存在"（不丢）
 *  2. dispatchAfterCommit() —— 提交后立刻投递：保证低延迟（大多数情况下毫秒级送达）
 *  3. retryPending()/compensate() —— 定时重试 + 对账补偿：保证"即使当时投不出去，最终也能到"
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
     * 为什么要这个映射：本地消息表存的是 JSON 字符串，投递时需要还原成原本的事件对象，
     * 才能让下游的消息转换器按类型正确反序列化。
     *
     * 【新增消息类型时必须在这里登记】漏登记的后果是**静默失败**：record 写库成功、业务也成功，
     * 但投递时抛"未注册的消息类型"，消息在表里反复重试到失败上限 —— 实测踩过：
     * 清购物车事件（CART_CLEAR）就是漏了这一步，日志里只有一行 `未注册的消息类型: CART_CLEAR`，
     * 业务侧表现只是"下单成功但购物车没清掉"。
     */
    private static final Map<String, Class<?>> PAYLOAD_TYPES = new HashMap<>();

    static {
        PAYLOAD_TYPES.put(MqConstants.BIZ_ORDER_PAID, OrderPaidEvent.class);
        PAYLOAD_TYPES.put(MqConstants.BIZ_CART_CLEAR, CartClearEvent.class);
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
                    action.run();
                }
            });
        } else {
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
