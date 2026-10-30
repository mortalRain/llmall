package com.llmall.pay.service.impl;

import com.llmall.pay.domain.po.PaymentAttempt;
import com.llmall.pay.mapper.PaymentAttemptMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentSuccessPublisherTest {
    private void publish(boolean ack, boolean returned) {
        RabbitTemplate rabbit=mock(RabbitTemplate.class);PaymentAttemptMapper attempts=mock(PaymentAttemptMapper.class);
        doAnswer(call->{
            CorrelationData data=call.getArgument(3);
            if(returned) data.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"NO_ROUTE","pay","success"));
            data.getFuture().set(new CorrelationData.Confirm(ack,ack?null:"nack"));return null;
        }).when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
        new PaymentSuccessPublisher(rabbit,attempts).publish(new PaymentAttempt().setId(1L).setBizOrderNo(100L));
        if(ack&&!returned) verify(attempts).notified(1L);else verify(attempts,never()).notified(anyLong());
    }
    @Test void routedAckMarksDurableEventNotified(){publish(true,false);}
    @Test void nackKeepsDurableEventPending(){publish(false,false);}
    @Test void returnedMessageIsNotDeliveryEvenWithAck(){publish(true,true);}
    @Test void transportFailureDoesNotTurnPaidOrderIntoFailedPayment(){
        RabbitTemplate rabbit=mock(RabbitTemplate.class);PaymentAttemptMapper attempts=mock(PaymentAttemptMapper.class);
        doThrow(new RuntimeException("connection lost")).when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
        assertDoesNotThrow(()->new PaymentSuccessPublisher(rabbit,attempts).publish(new PaymentAttempt().setId(1L).setBizOrderNo(100L)));
        verify(attempts,never()).notified(anyLong());
    }
}
