package com.llmall.shop.task;

import com.llmall.common.mq.AbstractMessageRetryTask;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.shop.domain.po.MessageLog;
import org.springframework.stereotype.Component;

/**
 * 门店域本地消息重试任务：兜住"消息已落库但没投递出去"。
 *
 * 没有它消息会永远躺在表里，表现为"评价成功、门店热度与搜索索引不更新"且无任何信号。
 * 与 {@link ShopSearchReconcileTask} 的分工：本任务兜发送侧失败，对账任务兜"发出去了但下游没处理成功"。
 * 调度间隔与退避策略见基类。
 */
@Component
public class MessageRetryTask extends AbstractMessageRetryTask<MessageLog> {

    public MessageRetryTask(IMessageLogService<MessageLog> messageLogService) {
        super(messageLogService);
    }
}
