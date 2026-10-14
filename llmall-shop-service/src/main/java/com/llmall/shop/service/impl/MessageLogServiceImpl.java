package com.llmall.shop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.mq.AbstractMessageLogService;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.shop.domain.po.MessageLog;
import com.llmall.shop.event.ShopDocChangedEvent;
import com.llmall.shop.mapper.MessageLogMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * 门店域本地消息表：投递、重试、对账补偿逻辑全部复用 {@link AbstractMessageLogService}。
 *
 * 与 {@code ShopSearchReconcileTask} 的分工：本类兜"发得出去"，对账任务兜"发出去了但下游确实处理了"。
 */
@Service
public class MessageLogServiceImpl extends AbstractMessageLogService<MessageLog, MessageLogMapper>
        implements IMessageLogService<MessageLog> {

    /** bizType → 消息体目标类型，新增事件类型必须在此登记（原因见基类 payloadTypes 的说明） */
    private static final Map<String, Class<?>> PAYLOAD_TYPES =
            Collections.singletonMap(MqConstants.BIZ_SHOP_HOT_CHANGED, ShopDocChangedEvent.class);

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
