package com.llmall.common.mq;

import com.llmall.common.config.MqConfig;
import com.llmall.common.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OutboxExecutorTest {
    @Test void saturatedQueueRejectsInsteadOfRunningBrokerWorkOnCaller() throws Exception {
        ThreadPoolTaskExecutor executor=new MqConfig().outboxDispatchExecutor();executor.initialize();
        CountDownLatch running=new CountDownLatch(2),release=new CountDownLatch(1);
        try {
            Runnable blocked=()->{running.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
            executor.execute(blocked);executor.execute(blocked);
            assertTrue(running.await(2,TimeUnit.SECONDS));
            for(int i=0;i<64;i++) executor.execute(()->{});
            java.util.concurrent.atomic.AtomicBoolean called=new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(org.springframework.core.task.TaskRejectedException.class,()->executor.execute(()->called.set(true)));
            assertFalse(called.get());
            assertEquals(64,executor.getThreadPoolExecutor().getQueue().size());
        } finally {release.countDown();executor.shutdown();}
    }
    @Test void traceFollowsTaskAndDoesNotLeakIntoNextAnonymousSubmission() throws Exception {
        ThreadPoolTaskExecutor executor=new MqConfig().outboxDispatchExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.initialize();
        try {
            TraceContext.set("fixture-request");
            assertEquals("fixture-request",executor.submit(TraceContext::current).get(2,TimeUnit.SECONDS));
            TraceContext.clear();
            assertNull(executor.submit(TraceContext::current).get(2,TimeUnit.SECONDS));
        } finally {TraceContext.clear();executor.shutdown();}
    }
    @Test void invalidConfirmationTimeoutMustNotPublish() {
        org.springframework.amqp.rabbit.core.RabbitTemplate rabbit=org.mockito.Mockito.mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        assertThrows(IllegalArgumentException.class,()->ConfirmedMessagePublisher.convertAndSend(rabbit,"events","key","payload",0));
        org.mockito.Mockito.verifyNoInteractions(rabbit);
    }
}
