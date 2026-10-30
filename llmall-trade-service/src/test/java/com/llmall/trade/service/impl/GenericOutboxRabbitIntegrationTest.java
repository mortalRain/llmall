package com.llmall.trade.service.impl;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.event.CartClearEvent;
import com.llmall.common.config.MqConfig;
import com.llmall.common.config.MqDeadLetterConfig;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.mq.ConfirmedMessagePublisher;
import com.llmall.common.trace.TraceContext;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: real private MySQL schema + owned Rabbit fixture, never a business schema or broker. */
@Tag("integration")
class GenericOutboxRabbitIntegrationTest {
    private static final String RUN=UUID.randomUUID().toString().replace("-", "").substring(0,12);
    private static final String SCHEMA="llmall_outbox_test_"+RUN;
    private static final String APP="fixture-"+RUN;
    private static String mysqlUrl(String schema){return "jdbc:mysql://127.0.0.1:3307/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=3000&socketTimeout=10000";}

    @Configuration @EnableTransactionManagement(proxyTargetClass=true) @Import(MqDeadLetterConfig.class)
    static class Config extends GenericOutboxTransactionTest.Config {
        @Bean @Override DataSource dataSource() {
            String password=Objects.requireNonNull(System.getenv("LLMALL_TEST_DB_PASSWORD"));
            try(Connection c=DriverManager.getConnection(mysqlUrl(""),"root",password);Statement s=c.createStatement()){
                assertTrue(SCHEMA.matches("llmall_outbox_test_[0-9a-f]{12}"));s.execute("CREATE DATABASE `"+SCHEMA+"`");
            }catch(SQLException e){throw new IllegalStateException(e);}
            HikariDataSource pool=new HikariDataSource();pool.setJdbcUrl(mysqlUrl(SCHEMA));pool.setUsername("root");pool.setPassword(password);
            pool.setMaximumPoolSize(3);pool.setMinimumIdle(1);pool.setConnectionTimeout(3000);return pool;
        }
        @Bean CachingConnectionFactory connectionFactory(){
            CachingConnectionFactory f=new CachingConnectionFactory("127.0.0.1",Integer.parseInt(Objects.requireNonNull(System.getenv("LLMALL_MQ_TEST_PORT"))));
            f.setUsername("fixture");f.setPassword(Objects.requireNonNull(System.getenv("LLMALL_MQ_TEST_PASSWORD")));f.setConnectionTimeout(2000);f.setChannelCacheSize(4);return f;
        }
        @Bean @Override RabbitTemplate rabbit(){RabbitTemplate t=new RabbitTemplate(connectionFactory());t.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));return t;}
        @Bean(name="outboxDispatchExecutor") @Override ThreadPoolTaskExecutor executor(){return new MqConfig().outboxDispatchExecutor();}
        @Bean static BeanPostProcessor postProcessor(){return MqConfig.rabbitTemplateTraceIdPostProcessor();}
    }

    @Test void confirmedOutboxAndFailedDeadLetterPreserveRecoverability() throws Exception {
        Path output=Path.of(Objects.requireNonNull(System.getenv("LLMALL_MQ_TEST_OUTPUT")));
        assertFalse(Files.exists(output),"Fresh output directory required");Files.createDirectories(output);
        Map<String,Object> results=new LinkedHashMap<>();List<String> checks=new ArrayList<>();
        results.put("completed",false);results.put("schema",SCHEMA);results.put("application",APP);results.put("checks",checks);
        ((ch.qos.logback.classic.Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.ERROR);
        AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("spring.application.name",APP,"llmall.mq.confirm-timeout-ms","1000")));
        SimpleMessageListenerContainer listener=null;
        HikariDataSource pool=null;
        try {
            context.register(Config.class);context.refresh();pool=(HikariDataSource)context.getBean(DataSource.class);
            JdbcTemplate db=new JdbcTemplate(pool);
            db.execute("CREATE TABLE message_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,biz_type VARCHAR(64),biz_id VARCHAR(64),exchange VARCHAR(100),routing_key VARCHAR(100),payload TEXT,status INT,retry_count INT,next_retry_time DATETIME(3),error_msg VARCHAR(1024),create_time DATETIME,update_time DATETIME,UNIQUE(biz_type,biz_id)) ENGINE=InnoDB");
            MessageLogServiceImpl service=context.getBean(MessageLogServiceImpl.class);
            CachingConnectionFactory cf=context.getBean(CachingConnectionFactory.class);
            RabbitTemplate rabbit=context.getBean(RabbitTemplate.class);RabbitAdmin admin=new RabbitAdmin(cf);
            String exchange=APP+".events",queue=APP+".events.queue",source=APP+".source",full=APP+".full";
            TopicExchange events=new TopicExchange(exchange);admin.declareExchange(events);admin.declareQueue(new Queue(queue));
            admin.declareBinding(BindingBuilder.bind(new Queue(queue)).to(events).with("cart.clear"));
            TransactionTemplate tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            TraceContext.set("fixture-commit-trace");
            tx.executeWithoutResult(s->{
                record(service,"commit",exchange,"cart.clear");service.dispatchAfterCommit(MqConstants.BIZ_CART_CLEAR,"commit");
                assertEquals(0,admin.getQueueProperties(queue).get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
            });
            TraceContext.clear();
            await(()->status(db,"commit")==1);
            Message event=rabbit.receive(queue,2000);assertNotNull(event);
            assertEquals("fixture-commit-trace",event.getMessageProperties().getHeader(TraceContext.HEADER));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM message_log WHERE biz_id='commit' AND status=1",Integer.class));
            checks.add("afterCommit ack persists SENT in fresh MySQL transaction; original trace preserved");

            record(service,"return",exchange,"unroutable");
            assertFalse(service.compensate(MqConstants.BIZ_CART_CLEAR,"return"));assertEquals(3,status(db,"return"));
            admin.declareBinding(BindingBuilder.bind(new Queue(queue)).to(events).with("unroutable"));
            db.update("UPDATE message_log SET next_retry_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE biz_id='return'");
            assertEquals(1,service.retryPending(10));assertEquals(1,status(db,"return"));assertNotNull(rabbit.receive(queue,2000));
            checks.add("real mandatory return keeps FAILED; repaired route and scheduled retry deliver");

            admin.declareQueue(QueueBuilder.durable(full).withArgument("x-max-length",1).withArgument("x-overflow","reject-publish").build());
            admin.declareBinding(BindingBuilder.bind(new Queue(full)).to(events).with("full"));
            ConfirmedMessagePublisher.send(rabbit,exchange,"full",new Message(new byte[]{7}),1000);
            record(service,"nack",exchange,"full");assertFalse(service.compensate(MqConstants.BIZ_CART_CLEAR,"nack"));assertEquals(3,status(db,"nack"));
            assertArrayEquals(new byte[]{7},Objects.requireNonNull(rabbit.receive(full,2000)).getBody());
            assertTrue(service.compensate(MqConstants.BIZ_CART_CLEAR,"nack"));assertNotNull(rabbit.receive(full,2000));
            checks.add("real broker nack from full reject-publish queue cannot mark SENT; later retry succeeds");

            int port=cf.getPort();cf.setPort(1);cf.resetConnection();
            record(service,"offline",exchange,"cart.clear");assertFalse(service.compensate(MqConstants.BIZ_CART_CLEAR,"offline"));assertEquals(3,status(db,"offline"));
            cf.setPort(port);cf.resetConnection();assertTrue(service.compensate(MqConstants.BIZ_CART_CLEAR,"offline"));assertNotNull(rabbit.receive(queue,2000));
            checks.add("real connection failure remains retryable and recovers");

            MqDeadLetterConfig config=context.getBean(MqDeadLetterConfig.class);
            TopicExchange dlx=config.deadLetterExchange();Queue own=config.deadLetterQueue(APP),other=config.deadLetterQueue("fixture-other-"+RUN);
            admin.declareExchange(dlx);admin.declareQueue(own);admin.declareQueue(other);
            Binding ownBinding=config.deadLetterBinding(own,dlx);admin.declareBinding(ownBinding);admin.declareBinding(config.deadLetterBinding(other,dlx));
            admin.declareQueue(new Queue(source));
            MessageRecoverer recovery=config.deadLetterRecoverer(rabbit);AtomicInteger failedForward=new AtomicInteger();
            MessageRecoverer observed=(message,cause)->{try{recovery.recover(message,cause);}catch(ImmediateRequeueAmqpException failure){failedForward.incrementAndGet();throw failure;}};
            listener=new SimpleMessageListenerContainer(cf);listener.setQueueNames(source);listener.setPrefetchCount(1);listener.setConcurrentConsumers(1);listener.setDefaultRequeueRejected(false);listener.setShutdownTimeout(3000);
            listener.setMessageListener((MessageListener)message->{throw new IllegalStateException("synthetic consumer failure");});
            listener.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(2).backOffOptions(100,1,100).recoverer(observed).build());
            admin.removeBinding(ownBinding);listener.start();
            ConfirmedMessagePublisher.send(rabbit,"",source,new Message(new byte[]{1,2,3}),1000);
            await(()->failedForward.get()>0);listener.stop();
            Message retained=rabbit.receive(source,2000);assertNotNull(retained,"Failed forwarding must retain source despite defaultRequeueRejected=false");assertArrayEquals(new byte[]{1,2,3},retained.getBody());
            assertNull(rabbit.receive(own.getName(),100));assertNull(rabbit.receive(other.getName(),100));
            checks.add("real listener recovery failure requeues original instead of acknowledging or dropping it");
            admin.declareBinding(ownBinding);listener.start();
            ConfirmedMessagePublisher.send(rabbit,"",source,retained,1000);
            Message forwarded=rabbit.receive(own.getName(),5000);assertNotNull(forwarded);assertArrayEquals(new byte[]{1,2,3},forwarded.getBody());
            assertEquals(source,forwarded.getMessageProperties().getHeader("x-original-queue"));assertNull(rabbit.receive(other.getName(),100));
            listener.stop();assertNull(rabbit.receive(source,100));
            checks.add("restored dead-letter route confirms forwarding, acknowledges source and isolates other application's queue");
            results.put("messageRows",db.queryForObject("SELECT COUNT(*) FROM message_log",Integer.class));
            results.put("completed",true);
        } finally {
            TraceContext.clear();if(listener!=null){listener.stop();listener.destroy();}
            context.close();results.put("dbPoolClosed",pool==null||pool.isClosed());results.put("contextClosed",!context.isActive());
            results.put("schemaRetained",true);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),results);
        }
    }
    private static void record(MessageLogServiceImpl service,String id,String exchange,String key){service.record(MqConstants.BIZ_CART_CLEAR,id,exchange,key,new CartClearEvent(1L,List.of(1L)));}
    private static int status(JdbcTemplate db,String id){return db.queryForObject("SELECT status FROM message_log WHERE biz_id=?",Integer.class,id);}
    private static void await(BooleanSupplier condition)throws InterruptedException{long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("Timed out waiting for durable state");Thread.sleep(30);}}
}
