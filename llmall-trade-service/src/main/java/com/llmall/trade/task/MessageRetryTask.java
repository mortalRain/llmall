package com.llmall.trade.task;

import com.llmall.common.mq.AbstractMessageRetryTask;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.trade.domain.po.MessageLog;
import org.springframework.stereotype.Component;

/**
 * 交易域本地消息重试任务：兜住"消息已落库但没投递出去"。
 *
 * 调度间隔、退避策略与 {@code @EnableScheduling} 的前置要求见基类。
 */
@Component
public class MessageRetryTask extends AbstractMessageRetryTask<MessageLog> {

    public MessageRetryTask(IMessageLogService<MessageLog> messageLogService) {
        super(messageLogService);
    }
}
