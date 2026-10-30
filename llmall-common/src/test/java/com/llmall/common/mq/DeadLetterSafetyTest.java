package com.llmall.common.mq;

import com.llmall.common.config.MqDeadLetterConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DeadLetterSafetyTest {
    private Message message(){MessageProperties p=new MessageProperties();p.setConsumerQueue("orders");return new Message(new byte[]{1,2},p);}
    @Test void forwardingFailureMustNotAcknowledgeOriginal(){
        RabbitTemplate rabbit=mock(RabbitTemplate.class);
        doThrow(new IllegalStateException("offline")).when(rabbit).send(anyString(),anyString(),any(Message.class));
        doThrow(new IllegalStateException("offline")).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThrows(ImmediateRequeueAmqpException.class,()->new MqDeadLetterConfig().deadLetterRecoverer(rabbit).recover(message(),new RuntimeException("consumer")));
    }
    @Test void nackIsNotAForwardingSuccess(){
        RabbitTemplate rabbit=mock(RabbitTemplate.class);
        doAnswer(c->{CorrelationData d=c.getArgument(3);d.getFuture().set(new CorrelationData.Confirm(false,"nack"));return null;})
                .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertFalse(DeadLetterPublisher.publish(rabbit,message(),"orders",new RuntimeException("consumer")));
    }
    @Test void alreadyMarkedMessageMustRemainAvailable(){
        RabbitTemplate rabbit=mock(RabbitTemplate.class);Message original=message();original.getMessageProperties().setHeader(DeadLetterPublisher.HEADER_DEAD_LETTERED,true);
        assertThrows(ImmediateRequeueAmqpException.class,()->new MqDeadLetterConfig().deadLetterRecoverer(rabbit).recover(original,new RuntimeException("consumer")));
        verifyNoInteractions(rabbit);
    }
    @Test void deadLetterBindingMustBePrivateToApplication(){
        MqDeadLetterConfig config=new MqDeadLetterConfig();Queue queue=config.deadLetterQueue("trade");
        Binding binding=config.deadLetterBinding(queue,config.deadLetterExchange());assertEquals(queue.getName(),binding.getRoutingKey());
    }
}
