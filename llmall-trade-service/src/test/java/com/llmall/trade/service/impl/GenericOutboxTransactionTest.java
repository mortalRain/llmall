package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.event.CartClearEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.trade.domain.po.MessageLog;
import com.llmall.trade.mapper.MessageLogMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GenericOutboxTransactionTest {
    enum Mode { ACK, NACK, RETURN, THROW, NO_CONFIRM, PEER_SENT_NACK, PEER_DONE_ACK }
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate db;
    private MessageLogServiceImpl service;
    private RabbitTemplate rabbit;
    private Mode mode;

    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    static class Config {
        @Bean DataSource dataSource() { JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");return ds; }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) {return new DataSourceTransactionManager(ds);}
        @Bean SqlSessionTemplate sql(DataSource ds)throws Exception {
            MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);
            MybatisConfiguration cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(MessageLogMapper.class);factory.setConfiguration(cfg);
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean RabbitTemplate rabbit() {return mock(RabbitTemplate.class);}
        @Bean MessageLogMapper mapper(SqlSessionTemplate sql){return sql.getMapper(MessageLogMapper.class);}
        @Bean org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(SqlSessionTemplate sql){return sql.getSqlSessionFactory();}
        @Bean(name="outboxDispatchExecutor") Executor executor(){return Runnable::run;}
        @Bean MessageLogServiceImpl service(RabbitTemplate rabbit,SqlSessionTemplate sql) {
            MessageLogServiceImpl result=new MessageLogServiceImpl(rabbit,new ObjectMapper());
            ReflectionTestUtils.setField(result,"baseMapper",sql.getMapper(MessageLogMapper.class));return result;
        }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("llmall.mq.confirm-timeout-ms","50")));
        context.register(Config.class);context.refresh();
        db=new JdbcTemplate(context.getBean(DataSource.class));
        db.execute("CREATE TABLE message_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_type VARCHAR(64),biz_id VARCHAR(64),exchange VARCHAR(100),routing_key VARCHAR(100),payload CLOB,status INT,retry_count INT,next_retry_time TIMESTAMP,error_msg VARCHAR(1024),create_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(biz_type,biz_id))");
        service=context.getBean(MessageLogServiceImpl.class);rabbit=context.getBean(RabbitTemplate.class);mode=Mode.ACK;
        clearInvocations(rabbit); // Ignore Spring's aware/initialization lifecycle, not any publication.
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"No broker wait inside a DB transaction");
            CorrelationData correlation=call.getArgument(3);
            if(mode==Mode.THROW)throw new IllegalStateException("offline");
            if(mode==Mode.NO_CONFIRM)return null;
            if(mode==Mode.PEER_SENT_NACK)db.update("UPDATE message_log SET status=1");
            if(mode==Mode.PEER_DONE_ACK)db.update("UPDATE message_log SET status=2");
            if(mode==Mode.RETURN)correlation.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"NO_ROUTE","events","unroutable"));
            correlation.getFuture().set(new CorrelationData.Confirm(mode!=Mode.NACK&&mode!=Mode.PEER_SENT_NACK,"fixture"));return null;
        }).when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
    }
    @AfterEach void cleanup(){db.execute("DROP ALL OBJECTS");context.close();}
    private MessageLog record(){service.record(MqConstants.BIZ_CART_CLEAR,"1","events","cart.clear",new CartClearEvent(1L,List.of(1L)));return service.list().get(0);}
    private int status(){return db.queryForObject("SELECT status FROM message_log",Integer.class);}
    private void failed(Mode failure){MessageLog row=record();mode=failure;assertFalse(service.dispatch(row));assertEquals(3,status());assertEquals(1,db.queryForObject("SELECT retry_count FROM message_log",Integer.class));}
    @Test void ackAndNoReturnMarksSent(){assertTrue(service.dispatch(record()));assertEquals(1,status());}
    @Test void nackRemainsRetryable(){failed(Mode.NACK);}
    @Test void returnedAckRemainsRetryable(){failed(Mode.RETURN);}
    @Test void transportFailureRemainsRetryable(){failed(Mode.THROW);}
    @Test void noConfirmationIsNotSuccessfulSend(){failed(Mode.NO_CONFIRM);}
    @Test void recoveryCanSendPreviouslyFailedRow(){failed(Mode.NACK);mode=Mode.ACK;assertTrue(service.dispatch(service.list().get(0)));assertEquals(1,status());}
    @Test void completedConsumerStateCannotBeDowngraded(){MessageLog row=record();service.markDone(row.getId());assertTrue(service.dispatch(row));assertEquals(2,status());verifyNoInteractions(rabbit);}
    @Test void aLateNackCannotDowngradeAnotherPublishersSuccess(){MessageLog row=record();mode=Mode.PEER_SENT_NACK;assertFalse(service.dispatch(row));assertEquals(1,status());}
    @Test void consumerCompletingBeforeAckKeepsDone(){mode=Mode.PEER_DONE_ACK;assertTrue(service.dispatch(record()));assertEquals(2,status());}
    @Test void afterCommitStatusReallyCommits(){
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(tx->{record();service.dispatchAfterCommit(MqConstants.BIZ_CART_CLEAR,"1");verifyNoInteractions(rabbit);});
        assertEquals(1,status());
    }
    @Test void rollbackPublishesNothing(){
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(tx->{record();service.dispatchAfterCommit(MqConstants.BIZ_CART_CLEAR,"1");tx.setRollbackOnly();});
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM message_log",Integer.class));verifyNoInteractions(rabbit);
    }
    @Test void saturatedExecutorKeepsCommittedPendingRowForScannerRecovery(){
        ReflectionTestUtils.setField(service,"dispatchExecutor",(Executor)task->{throw new RejectedExecutionException("fixture full");});
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(tx->{record();service.dispatchAfterCommit(MqConstants.BIZ_CART_CLEAR,"1");});
        assertEquals(0,status());verifyNoInteractions(rabbit);
        assertEquals(1,service.retryPending(10));assertEquals(1,status());
    }
}
