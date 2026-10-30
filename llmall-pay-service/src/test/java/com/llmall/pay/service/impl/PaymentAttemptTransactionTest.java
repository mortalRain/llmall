package com.llmall.pay.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.api.client.BalancePaymentClient;
import com.llmall.api.client.TradeClient;
import com.llmall.common.utils.UserContext;
import com.llmall.pay.domain.po.PaymentAttempt;
import com.llmall.pay.mapper.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

class PaymentAttemptTransactionTest {
    private AnnotationConfigApplicationContext context;
    private PaymentAttemptStore store;
    private PayCloseService close;
    private JdbcTemplate db;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return ds;
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionTemplate sql(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds);
            MybatisConfiguration cfg = new MybatisConfiguration(); cfg.setMapUnderscoreToCamelCase(true);
            cfg.addMapper(PayOrderMapper.class); cfg.addMapper(PaymentAttemptMapper.class); factory.setConfiguration(cfg);
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean PaymentAttemptStore store(SqlSessionTemplate sql) {return new PaymentAttemptStore(sql.getMapper(PayOrderMapper.class),sql.getMapper(PaymentAttemptMapper.class));}
        @Bean PayCloseService close(SqlSessionTemplate sql) {return new PayCloseService(sql.getMapper(PayOrderMapper.class));}
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class); store=context.getBean(PaymentAttemptStore.class);
        close=context.getBean(PayCloseService.class); db=new JdbcTemplate(context.getBean(DataSource.class));
        db.execute("CREATE TABLE pay_order(id BIGINT PRIMARY KEY, biz_order_no BIGINT UNIQUE, pay_order_no BIGINT UNIQUE, biz_user_id BIGINT, pay_channel_code VARCHAR(32), amount INT, pay_type INT, status INT, expand_json VARCHAR(255), result_code VARCHAR(64), result_msg VARCHAR(255), pay_success_time TIMESTAMP, pay_over_time TIMESTAMP, qr_code_url VARCHAR(255), create_time TIMESTAMP, update_time TIMESTAMP, creater BIGINT, updater BIGINT, is_delete BOOLEAN)");
        db.execute("CREATE TABLE payment_attempt(id BIGINT PRIMARY KEY, pay_order_id BIGINT, biz_order_no BIGINT, user_id BIGINT, amount INT, state VARCHAR(16), notified BOOLEAN, create_time TIMESTAMP, next_check_time TIMESTAMP)");
        db.update("INSERT INTO pay_order(id,biz_order_no,biz_user_id,amount,status) VALUES(10,100,1,300,1)");
    }
    @AfterEach void cleanup() {UserContext.removeUser();db.execute("DROP ALL OBJECTS");context.close();}
    private int status() {return db.queryForObject("SELECT status FROM pay_order WHERE id=10",Integer.class);}
    private String state(PaymentAttempt a) {return db.queryForObject("SELECT state FROM payment_attempt WHERE id=?",String.class,a.getId());}
    private TradeOrderDTO trade(long id) {TradeOrderDTO t=new TradeOrderDTO();t.setId(id);t.setUserId(1L);t.setTotalFee(300);return t;}

    @Test void claimCommitsProcessingBeforeRemoteWorkAndRejectsSecondClaim() {
        PaymentAttempt first=store.claim(10L,1L);
        assertEquals(4,status()); assertEquals("ACTIVE",state(first));
        assertThrows(RuntimeException.class,()->store.claim(10L,1L));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_attempt",Integer.class));
    }
    @Test void canceledAttemptCannotCompleteOrResetNewAttempt() {
        PaymentAttempt first=store.claim(10L,1L); assertTrue(store.beginCancel(first)); store.finishCancel(first);
        PaymentAttempt second=store.claim(10L,1L);
        assertNotEquals(first.debitKey(),second.debitKey());
        assertThrows(RuntimeException.class,()->store.complete(first));
        assertFalse(store.beginCancel(first));store.finishCancel(first);
        assertEquals(4,status()); assertEquals("ACTIVE",state(second));
        store.complete(second); assertEquals(3,status()); assertEquals("SUCCEEDED",state(second));
    }
    @Test void succeededAttemptCannotBeCanceledAfterLostCommitAcknowledgement() {
        PaymentAttempt first=store.claim(10L,1L);store.complete(first);
        assertFalse(store.beginCancel(first)); store.finishCancel(first);
        assertTrue(store.succeeded(first.getId())); assertEquals(3,status());
    }
    @Test void attemptInsertRollsBackWhenOrderUpdateFails() {
        db.execute("ALTER TABLE pay_order ADD CONSTRAINT reject_processing CHECK(status <> 4)");
        assertThrows(RuntimeException.class,()->store.claim(10L,1L));
        assertEquals(1,status());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM payment_attempt",Integer.class));
    }
    @Test void closingUnpaidCannotCloseActivePayment() {
        store.claim(10L,1L);
        assertFalse(close.closeUnpaid(trade(100L)).isDetermined()); assertEquals(4,status());
    }
    @Test void absentPaymentIsFencedByClosedPlaceholder() {
        assertEquals(2,close.closeUnpaid(trade(200L)).getPayStatus());
        Long id=db.queryForObject("SELECT id FROM pay_order WHERE biz_order_no=200",Long.class);
        assertThrows(RuntimeException.class,()->store.claim(id,1L));
        assertEquals(2,close.closeUnpaid(trade(200L)).getPayStatus());
    }
    @Test void completeAndCancelRaceHasExactlyOneWinner() throws Exception {
        PaymentAttempt first=store.claim(10L,1L);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> completed=pool.submit(()->{start.await();try{store.complete(first);return true;}catch(RuntimeException e){return false;}});
            Future<Boolean> canceled=pool.submit(()->{start.await();return store.beginCancel(first);});
            start.countDown();boolean success=completed.get(15,TimeUnit.SECONDS);boolean cancel=canceled.get(15,TimeUnit.SECONDS);
            assertNotEquals(success,cancel);
            if(success){assertEquals(3,status());assertEquals("SUCCEEDED",state(first));}
            else{assertEquals(4,status());assertEquals("CANCELING",state(first));store.finishCancel(first);assertEquals(1,status());}
        }finally{pool.shutdownNow();}
    }
    @Test void foreignUserCannotCreateAttempt() {
        assertThrows(RuntimeException.class,()->store.claim(10L,2L));
        assertEquals(1,status());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM payment_attempt",Integer.class));
    }
    @Test void legacyUncertainPaymentCannotStartNewAttemptOrBeClosed() {
        db.update("UPDATE pay_order SET result_code='UNCERTAIN' WHERE id=10");
        assertThrows(RuntimeException.class,()->store.claim(10L,1L));
        assertFalse(close.closeUnpaid(trade(100L)).isDetermined()); assertEquals(1,status());
    }
    @Test void lostDebitResponseAndFailedCancelRemainRecoverable() {
        BalancePaymentClient balance=mock(BalancePaymentClient.class);TradeClient trade=mock(TradeClient.class);
        PaymentAttemptMapper scan=mock(PaymentAttemptMapper.class);PaymentSuccessPublisher publisher=mock(PaymentSuccessPublisher.class);
        TradeOrderDTO order=trade(100L);order.setStatus(1);when(trade.queryOrderById(100L)).thenReturn(order);
        doThrow(new RuntimeException("response lost")).when(balance).debit(any());
        doThrow(new RuntimeException("user offline")).when(balance).cancel(any());
        BalancePaymentCoordinator coordinator=new BalancePaymentCoordinator(store,scan,balance,trade,null,publisher);
        UserContext.setUser(1L);assertThrows(RuntimeException.class,()->coordinator.pay(10L,"654321"));
        assertEquals(4,status());
        PaymentAttempt attempt=context.getBean(SqlSessionTemplate.class).getMapper(PaymentAttemptMapper.class)
                .selectById(db.queryForObject("SELECT id FROM payment_attempt",Long.class));
        assertEquals("CANCELING",state(attempt));
        when(scan.recoverable()).thenReturn(Collections.singletonList(attempt));
        doNothing().when(balance).cancel(any());coordinator.recover();
        assertEquals(1,status());assertEquals("CANCELED",state(attempt));verifyNoInteractions(publisher);
        verify(balance,times(2)).cancel(argThat(c->c.getKey().equals(attempt.debitKey())&&c.getAmount()==300&&c.getUserId()==1L));
    }
    @Test void unboundCouponCancelsAttemptAndCanRetryAfterBinding() {
        BalancePaymentClient balance=mock(BalancePaymentClient.class);
        TradeClient trade=mock(TradeClient.class);
        com.llmall.api.client.VoucherClient vouchers=mock(com.llmall.api.client.VoucherClient.class);
        PaymentSuccessPublisher publisher=mock(PaymentSuccessPublisher.class);
        TradeOrderDTO order=trade(100L);order.setStatus(1);order.setVoucherId(8L);
        when(trade.queryOrderById(100L)).thenReturn(order);
        com.llmall.api.dto.UserVoucherDTO voucher=new com.llmall.api.dto.UserVoucherDTO();
        voucher.setId(8L);voucher.setStatus(1);
        when(vouchers.queryMyVoucher(8L)).thenReturn(voucher);
        BalancePaymentCoordinator coordinator=new BalancePaymentCoordinator(store,mock(PaymentAttemptMapper.class),balance,trade,vouchers,publisher);
        UserContext.setUser(1L);
        assertThrows(RuntimeException.class,()->coordinator.pay(10L,"654321"));
        assertEquals(1,status());
        assertEquals("CANCELED",db.queryForObject("SELECT state FROM payment_attempt",String.class));
        verify(balance,never()).debit(any());verifyNoInteractions(publisher);
        voucher.setStatus(2);voucher.setOrderId(100L);
        coordinator.pay(10L,"654321");
        assertEquals(3,status());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE state='SUCCEEDED'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE state='CANCELED'",Integer.class));
        verify(balance,times(1)).debit(any());verify(publisher).publish(any());
    }

    @Test void lostCommitResponseNeverRefundsSuccessfulPayment() {
        PaymentAttemptStore lostAck=mock(PaymentAttemptStore.class, delegatesTo(store));
        doAnswer(call->{store.complete(call.getArgument(0));throw new RuntimeException("commit response lost");}).when(lostAck).complete(any());
        BalancePaymentClient balance=mock(BalancePaymentClient.class);TradeClient trade=mock(TradeClient.class);
        PaymentSuccessPublisher publisher=mock(PaymentSuccessPublisher.class);
        TradeOrderDTO order=trade(100L);order.setStatus(1);when(trade.queryOrderById(100L)).thenReturn(order);
        UserContext.setUser(1L);
        new BalancePaymentCoordinator(lostAck,mock(PaymentAttemptMapper.class),balance,trade,null,publisher).pay(10L,"654321");
        assertEquals(3,status());verify(balance,never()).cancel(any());verify(publisher).publish(any());
    }
}
