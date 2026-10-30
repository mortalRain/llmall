package com.llmall.user.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.llmall.api.dto.BalancePaymentCommand;
import com.llmall.common.utils.UserContext;
import com.llmall.user.mapper.*;
import com.llmall.user.support.PasswordAttemptGuard;
import com.llmall.user.support.PayPasswordVerifier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL, Spring transaction proxies and concurrent connections; no business database required. */
class BalancePaymentTransactionTest {
    private AnnotationConfigApplicationContext context;
    private BalancePaymentService service;
    private JdbcTemplate db;

    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return ds;
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionTemplate sql(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            MybatisConfiguration cfg = new MybatisConfiguration();
            cfg.setMapUnderscoreToCamelCase(true);
            cfg.addMapper(BalancePaymentMapper.class); cfg.addMapper(BalanceFlowMapper.class); cfg.addMapper(UserMapper.class);
            factory.setConfiguration(cfg);
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean PasswordAttemptGuard attempts() { return mock(PasswordAttemptGuard.class); }
        @Bean BalancePaymentService service(SqlSessionTemplate sql, PasswordAttemptGuard attempts) {
            PasswordEncoder encoder = mock(PasswordEncoder.class);
            when(encoder.matches("654321", "hash")).thenReturn(true);
            return new BalancePaymentService(sql.getMapper(BalancePaymentMapper.class), sql.getMapper(BalanceFlowMapper.class),
                    sql.getMapper(UserMapper.class), new PayPasswordVerifier(attempts, sql.getMapper(UserMapper.class), encoder));
        }
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(BalancePaymentService.class);
        db = new JdbcTemplate(context.getBean(DataSource.class));
        db.execute("CREATE TABLE user(id BIGINT PRIMARY KEY, username VARCHAR(64), nick_name VARCHAR(64), icon VARCHAR(255), password VARCHAR(64), pay_password VARCHAR(64), phone VARCHAR(32), status INT, balance INT, create_time TIMESTAMP, update_time TIMESTAMP)");
        db.execute("CREATE TABLE balance_payment(payment_key VARCHAR(64) PRIMARY KEY, user_id BIGINT, amount INT, state VARCHAR(16), update_time TIMESTAMP)");
        db.execute("CREATE TABLE balance_flow(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, change_amount INT, balance_after INT, biz_type VARCHAR(32), biz_no VARCHAR(64), remark VARCHAR(255), create_time TIMESTAMP, UNIQUE(biz_type,biz_no))");
        db.update("INSERT INTO user(id,pay_password,status,balance) VALUES(1,'hash',1,1000)");
        UserContext.setUser(1L);
    }
    @AfterEach void cleanup() { UserContext.removeUser(); db.execute("DROP ALL OBJECTS"); context.close(); }
    private BalancePaymentCommand command(long id) { return new BalancePaymentCommand().setKey("PAYMENT_V2:"+id).setUserId(1L).setAmount(300).setPassword("654321"); }
    private int balance() { return db.queryForObject("SELECT balance FROM user WHERE id=1", Integer.class); }
    private int flows() { return db.queryForObject("SELECT COUNT(*) FROM balance_flow", Integer.class); }
    private String state(long id) { return db.queryForObject("SELECT state FROM balance_payment WHERE payment_key=?",String.class,"PAYMENT_V2:"+id); }

    @Test void duplicateDebitMovesMoneyOnlyOnce() {
        service.debit(command(1)); service.debit(command(1));
        assertEquals(700,balance()); assertEquals(1,flows()); assertEquals("DEBITED",state(1));
    }
    @Test void guardRejectionCannotCreateOrDebitPayment() {
        PasswordAttemptGuard attempts = context.getBean(PasswordAttemptGuard.class);
        doThrow(new com.llmall.common.exception.BizIllegalException("locked")).when(attempts)
                .check(1L, PasswordAttemptGuard.Purpose.PAYMENT);
        assertThrows(RuntimeException.class, () -> service.debit(command(1)));
        assertEquals(1000, balance()); assertEquals(0, flows());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM balance_payment", Integer.class));
    }
    @Test void cancellationStillRefundsWhenPasswordGuardIsUnavailable() {
        service.debit(command(1));
        PasswordAttemptGuard attempts = context.getBean(PasswordAttemptGuard.class);
        doThrow(new IllegalStateException("fixture Redis outage")).when(attempts)
                .check(anyLong(), any());
        service.cancel(command(1)); service.cancel(command(1));
        assertEquals(1000, balance()); assertEquals(2, flows()); assertEquals("REFUNDED", state(1));
    }
    @Test void voucherPurchaseNamespaceIsIndependentFromOrderPayment() {
        BalancePaymentCommand voucher = command(1).setKey("VOUCHER_PURCHASE_V1:1");
        service.debit(voucher); service.debit(voucher);
        service.debit(command(1));
        assertEquals(400, balance());
        service.cancel(voucher); service.cancel(voucher);
        assertEquals(700, balance());
        assertEquals("DEBITED", state(1));
        service.cancel(command(1));
        assertEquals(1000, balance()); assertEquals(4, flows());
    }
    @Test void malformedVoucherKeysCannotCreateFundsOperations() {
        for (String key : new String[]{"VOUCHER_PURCHASE_V1:", "VOUCHER_PURCHASE_V1:abc", "VOUCHER_PURCHASE_V1:123456789012345678901", "VOUCHER_PURCHASE_V2:1"}) {
            assertThrows(RuntimeException.class, () -> service.debit(command(1).setKey(key)));
        }
        assertEquals(1000, balance()); assertEquals(0, flows());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM balance_payment", Integer.class));
    }
    @Test void cancelBeforeDebitPermanentlyRejectsLateDebit() {
        service.cancel(command(1));
        assertThrows(RuntimeException.class,()->service.debit(command(1)));
        assertEquals(1000,balance()); assertEquals(0,flows()); assertEquals("CANCELED",state(1));
    }
    @Test void refundedAttemptCannotPayAgainButNewAttemptCan() {
        service.debit(command(1)); service.cancel(command(1)); service.cancel(command(1));
        assertThrows(RuntimeException.class,()->service.debit(command(1)));
        assertEquals(1000,balance()); assertEquals("REFUNDED",state(1));
        service.debit(command(2));
        assertEquals(700,balance()); assertEquals(3,flows()); assertEquals("DEBITED",state(2));
    }
    @Test void changedAmountCannotReusePaymentKey() {
        service.debit(command(1));
        assertThrows(RuntimeException.class,()->service.cancel(command(1).setAmount(500)));
        assertEquals(700,balance()); assertEquals("DEBITED",state(1)); assertEquals(1,flows());
    }
    @Test void ledgerFailureRollsBackBalanceAndOperation() {
        db.execute("ALTER TABLE balance_flow ADD CONSTRAINT reject_flow CHECK(change_amount > 0)");
        assertThrows(RuntimeException.class,()->service.debit(command(1)));
        assertEquals(1000,balance()); assertEquals(0,flows());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM balance_payment",Integer.class));
    }
    @Test void refundLedgerFailureRollsBackRefundAndRemainsRetryable() {
        service.debit(command(1));
        db.execute("ALTER TABLE balance_flow ADD CONSTRAINT reject_refund CHECK(change_amount < 0)");
        assertThrows(RuntimeException.class,()->service.cancel(command(1)));
        assertEquals(700,balance()); assertEquals("DEBITED",state(1));
        db.execute("ALTER TABLE balance_flow DROP CONSTRAINT reject_refund");
        service.cancel(command(1)); assertEquals(1000,balance()); assertEquals(2,flows());
    }
    @Test void invalidIdentityOrPasswordCannotDebit() {
        UserContext.setUser(2L);
        assertThrows(RuntimeException.class,()->service.debit(command(1)));
        UserContext.setUser(1L);
        assertThrows(RuntimeException.class,()->service.debit(command(1).setPassword("wrong")));
        assertEquals(1000,balance()); assertEquals(0,flows());
    }
    @Test void concurrentDebitAndCancelNeverLeaveMoneyDeducted() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            for(long id=1;id<=12;id++) {
                final long attempt=id;
                CountDownLatch start=new CountDownLatch(1);
                Future<?> debit=pool.submit(()->{UserContext.setUser(1L);try {
                    start.await();
                    try { service.debit(command(attempt)); } catch(RuntimeException expectedIfCanceled) {
                        assertEquals("CANCELED",state(attempt));
                    }
                } catch(InterruptedException e) { throw new RuntimeException(e); } finally {UserContext.removeUser();}});
                Future<?> cancel=pool.submit(()->{try {start.await();service.cancel(command(attempt));}
                    catch(InterruptedException e){throw new RuntimeException(e);}});
                start.countDown(); debit.get(15,TimeUnit.SECONDS); cancel.get(15,TimeUnit.SECONDS);
                assertEquals(1000,balance());
                assertTrue(Arrays.asList("CANCELED","REFUNDED").contains(state(attempt)));
            }
            assertEquals(0,db.queryForObject("SELECT COALESCE(SUM(change_amount),0) FROM balance_flow",Integer.class));
        } finally {pool.shutdownNow();}
    }
}
