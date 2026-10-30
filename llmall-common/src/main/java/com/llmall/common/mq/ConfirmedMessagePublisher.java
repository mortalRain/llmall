package com.llmall.common.mq;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Broker acceptance and routing confirmation, not proof of consumer completion. */
public final class ConfirmedMessagePublisher {
    private ConfirmedMessagePublisher() { }

    public static void convertAndSend(RabbitTemplate rabbit, String exchange, String key, Object payload, long timeoutMillis) {
        CorrelationData correlation = correlation(rabbit, timeoutMillis);
        rabbit.convertAndSend(exchange, key, payload, correlation);
        await(correlation, timeoutMillis);
    }

    public static void send(RabbitTemplate rabbit, String exchange, String key, Message payload, long timeoutMillis) {
        CorrelationData correlation = correlation(rabbit, timeoutMillis);
        rabbit.send(exchange, key, payload, correlation);
        await(correlation, timeoutMillis);
    }

    private static CorrelationData correlation(RabbitTemplate rabbit, long timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 30000) throw new IllegalArgumentException("Invalid confirm timeout");
        rabbit.setMandatory(true);
        return new CorrelationData(UUID.randomUUID().toString());
    }

    private static void await(CorrelationData correlation, long timeoutMillis) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new AmqpException("Message was nacked or returned");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Confirmation interrupted", interrupted);
        } catch (Exception failure) {
            if (failure instanceof AmqpException) throw (AmqpException) failure;
            throw new AmqpException("Message confirmation unavailable", failure);
        }
    }
}
