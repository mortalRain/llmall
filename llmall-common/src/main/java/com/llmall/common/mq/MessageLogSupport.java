package com.llmall.common.mq;

import java.time.LocalDateTime;

/**
 * 本地消息表的行契约：各服务在自己库里持有结构一致的 {@code message_log} 表（见
 * sql/merged/02-shared-infra.sql、05-shop-message-log.sql、06-content-message-log.sql），
 * PO 实现本接口后即可复用 {@link AbstractMessageLogService} 的全部投递与重试逻辑。
 *
 * 【注意】三个服务的表结构必须保持一致：基类统一按列名读写，任何一方单独改列名都会静默查不到数据。
 * 各服务 PO 的 setter 由 Lombok {@code @Accessors(chain = true)} 生成、返回自身类型，
 * 协变返回类型满足本接口的声明。
 */
public interface MessageLogSupport {

    /** 待发送：已与业务同事务落库，尚未投递 */
    int STATUS_PENDING = 0;

    /** 已投递：MQ 已接收，但下游是否处理成功未知 */
    int STATUS_SENT = 1;

    /** 已完成：下游确认已处理（对账写入，对账窗口据此收窄） */
    int STATUS_DONE = 2;

    /** 失败待重试：投递失败，按 nextRetryTime 指数退避 */
    int STATUS_FAILED = 3;

    Long getId();

    String getBizType();

    String getBizId();

    String getExchange();

    String getRoutingKey();

    String getPayload();

    Integer getStatus();

    Integer getRetryCount();

    LocalDateTime getNextRetryTime();

    String getErrorMsg();

    MessageLogSupport setBizType(String bizType);

    MessageLogSupport setBizId(String bizId);

    MessageLogSupport setExchange(String exchange);

    MessageLogSupport setRoutingKey(String routingKey);

    MessageLogSupport setPayload(String payload);

    MessageLogSupport setStatus(Integer status);

    MessageLogSupport setRetryCount(Integer retryCount);

    MessageLogSupport setNextRetryTime(LocalDateTime nextRetryTime);

    MessageLogSupport setErrorMsg(String errorMsg);
}
