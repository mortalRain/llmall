package com.llmall.content.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.event.ReviewCreatedEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.mq.AbstractMessageLogService;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.content.domain.po.MessageLog;
import com.llmall.content.mapper.MessageLogMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * 内容域本地消息表（生产端）：投递、重试、序列化逻辑全部复用 {@link AbstractMessageLogService}。
 *
 * 【注意】record() 与业务写同事务，登记失败会连业务写一起回滚、接口返回 500 —— 这是刻意的，
 * 只记日志继续提交就会静默产生"评价存在、门店热度永不更新"。
 * 调用方也不能再套 {@code TxAfterCommit}：那会把 record() 挪到提交后脱离业务事务，
 * 且在 afterCommit 回调里嵌套注册的同步器不会被执行。
 */
@Service
public class MessageLogServiceImpl extends AbstractMessageLogService<MessageLog, MessageLogMapper>
        implements IMessageLogService<MessageLog> {

    /** bizType → 消息体目标类型，新增事件类型必须在此登记（原因见基类 payloadTypes 的说明） */
    private static final Map<String, Class<?>> PAYLOAD_TYPES =
            Collections.singletonMap(MqConstants.BIZ_REVIEW_CREATED, ReviewCreatedEvent.class);

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
