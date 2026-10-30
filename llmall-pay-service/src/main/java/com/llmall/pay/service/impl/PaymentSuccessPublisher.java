package com.llmall.pay.service.impl;

import com.llmall.common.constant.MqConstants;
import com.llmall.pay.domain.po.PaymentAttempt;
import com.llmall.pay.mapper.PaymentAttemptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/** The successful attempt is the durable outbox; broker confirmation never means consumer completion. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSuccessPublisher {
    private final RabbitTemplate rabbit;
    private final PaymentAttemptMapper attempts;

    public void publish(PaymentAttempt attempt) {
        CorrelationData correlation = new CorrelationData(attempt.getId() + ":" + UUID.randomUUID());
        try {
            rabbit.convertAndSend(MqConstants.PAY_EXCHANGE, MqConstants.PAY_SUCCESS_KEY,
                    attempt.getBizOrderNo(), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (confirm.isAck() && correlation.getReturned() == null) attempts.notified(attempt.getId());
            else log.warn("支付事件未被目标队列确认，将重投: attemptId={}", attempt.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("支付事件投递未确认，将重投: attemptId={}", attempt.getId(), e);
        }
    }
}
