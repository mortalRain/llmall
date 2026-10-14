package com.llmall.trade.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.event.CartClearEvent;
import com.llmall.api.event.OrderPaidEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.mq.AbstractMessageLogService;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.trade.domain.po.MessageLog;
import com.llmall.trade.mapper.MessageLogMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 交易域本地消息表：投递、重试、对账补偿逻辑全部复用 {@link AbstractMessageLogService}。
 *
 * 时序约束：record() 必须与业务写在同一事务（保证"业务成功 ⇒ 消息一定存在"）；
 * dispatchAfterCommit() 只在事务提交后投递，低延迟且回滚不发；
 * retryPending()/compensate() 负责退避重试与对账补偿，保证当时投不出去的消息最终也能到。
 */
@Service
public class MessageLogServiceImpl extends AbstractMessageLogService<MessageLog, MessageLogMapper>
        implements IMessageLogService<MessageLog> {

    /** bizType → 消息体目标类型，新增事件类型必须在此登记（原因见基类 payloadTypes 的说明） */
    private static final Map<String, Class<?>> PAYLOAD_TYPES = new HashMap<>();

    static {
        PAYLOAD_TYPES.put(MqConstants.BIZ_ORDER_PAID, OrderPaidEvent.class);
        PAYLOAD_TYPES.put(MqConstants.BIZ_CART_CLEAR, CartClearEvent.class);
    }

    public MessageLogServiceImpl(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        super(rabbitTemplate, objectMapper);
    }

    @Override
    protected MessageLog newMessageLog() {
        return new MessageLog();
    }

    @Override
    protected Map<String, Class<?>> payloadTypes() {
        return PAYLOAD_TYPES;
    }
}
