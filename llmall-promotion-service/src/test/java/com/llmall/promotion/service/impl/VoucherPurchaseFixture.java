package com.llmall.promotion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.llmall.api.client.BalancePaymentClient;
import com.llmall.api.dto.BalancePaymentCommand;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.utils.UserContext;
import com.llmall.promotion.mapper.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local SQL/transactions; funds substitute models the user domain's atomic debit/cancel contract. */
abstract class VoucherPurchaseFixture {
    protected AnnotationConfigApplicationContext context;
    protected JdbcTemplate db;
    protected VoucherPurchaseStore store;
    protected VoucherPurchaseMapper mapper;
    protected VoucherPurchaseCoordinator coordinator;
    protected Funds funds;
    protected BalancePaymentClient balance;
    protected static final String REQUEST = "purchase-request-0001";

    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return ds;
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionTemplate sql(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean f = new MybatisSqlSessionFactoryBean(); f.setDataSource(ds);
            MybatisConfiguration cfg = new MybatisConfiguration(); cfg.setMapUnderscoreToCamelCase(true);
            cfg.addMapper(VoucherMapper.class); cfg.addMapper(UserVoucherMapper.class);
            cfg.addMapper(SeckillVoucherMapper.class); cfg.addMapper(VoucherPurchaseMapper.class);
            f.setConfiguration(cfg); return new SqlSessionTemplate(f.getObject());
        }
        @Bean VoucherPurchaseStore store(SqlSessionTemplate sql) {
            return new VoucherPurchaseStore(sql.getMapper(VoucherPurchaseMapper.class), sql.getMapper(VoucherMapper.class),
                    sql.getMapper(SeckillVoucherMapper.class), sql.getMapper(UserVoucherMapper.class));
        }
    }
    @BeforeEach void setupPurchase() {
        context = new AnnotationConfigApplicationContext(Config.class);
        db = new JdbcTemplate(context.getBean(DataSource.class)); store = context.getBean(VoucherPurchaseStore.class);
        mapper = context.getBean(SqlSessionTemplate.class).getMapper(VoucherPurchaseMapper.class);
        db.execute("CREATE TABLE voucher(id BIGINT PRIMARY KEY,shop_id BIGINT,title VARCHAR(64),sub_title VARCHAR(64),rules VARCHAR(255),pay_value BIGINT,actual_value BIGINT,type INT,status INT,create_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute("CREATE TABLE seckill_voucher(voucher_id BIGINT PRIMARY KEY,stock INT,begin_time TIMESTAMP,end_time TIMESTAMP,create_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute("CREATE TABLE user_voucher(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,voucher_id BIGINT,shop_id BIGINT,order_id BIGINT,status INT,receive_time TIMESTAMP,use_time TIMESTAMP,expire_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(user_id,voucher_id))");
        db.execute("CREATE TABLE voucher_purchase(id BIGINT PRIMARY KEY,user_id BIGINT,voucher_id BIGINT,request_id VARCHAR(64),amount INT,shop_id BIGINT,expire_time TIMESTAMP,state VARCHAR(16),user_voucher_id BIGINT,create_time TIMESTAMP,next_check_time TIMESTAMP,UNIQUE(user_id,request_id))");
        db.update("INSERT INTO voucher(id,shop_id,pay_value,actual_value,status,rules) VALUES(9,3,1000,1200,1,'{\"validDays\":5}')");
        funds = new Funds(); balance = mock(BalancePaymentClient.class);
        doAnswer(c -> { funds.debit(c.getArgument(0)); return null; }).when(balance).debit(any());
        doAnswer(c -> { funds.cancel(c.getArgument(0)); return null; }).when(balance).cancel(any());
        coordinator = new VoucherPurchaseCoordinator(store, mapper, balance, mock(BizMetrics.class));
        UserContext.setUser(1L);
    }
    @AfterEach void cleanupPurchase() { UserContext.removeUser(); db.execute("DROP ALL OBJECTS"); context.close(); }
    protected long receive(String request) { return coordinator.receive(9L, "password", request); }
    protected int coupons() { return db.queryForObject("SELECT COUNT(*) FROM user_voucher", Integer.class); }
    protected int purchases() { return db.queryForObject("SELECT COUNT(*) FROM voucher_purchase", Integer.class); }
    protected String state() { return db.queryForObject("SELECT state FROM voucher_purchase WHERE user_id=1 AND request_id=?", String.class, REQUEST); }
    protected void due() { db.update("UPDATE voucher_purchase SET create_time=TIMESTAMP '2020-01-01 00:00:00',next_check_time=TIMESTAMP '2020-01-01 00:00:00'"); }

    protected static class Funds {
        int available = 10000, debits, cancels;
        volatile boolean loseDebitResponse, cancelUnavailable, loseCancelResponse;
        Runnable beforeDebit = () -> {}, afterDebit = () -> {};
        final Map<String, String> states = new HashMap<>();
        void debit(BalancePaymentCommand c) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "RPC must not hold a local transaction");
            beforeDebit.run();
            synchronized (this) {
                if (!"password".equals(c.getPassword())) throw new IllegalStateException("wrong password");
                String state = states.get(c.getKey());
                if ("CANCELED".equals(state) || "REFUNDED".equals(state)) throw new IllegalStateException("late debit fenced");
                if (!"DEBITED".equals(state)) { available -= c.getAmount(); debits++; states.put(c.getKey(), "DEBITED"); }
            }
            afterDebit.run();
            if (loseDebitResponse) throw new IllegalStateException("debit committed, response lost");
        }
        synchronized void cancel(BalancePaymentCommand c) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            cancels++;
            if (cancelUnavailable) throw new IllegalStateException("user unavailable");
            String state = states.get(c.getKey());
            if ("DEBITED".equals(state)) { available += c.getAmount(); states.put(c.getKey(), "REFUNDED"); }
            else if (state == null) states.put(c.getKey(), "CANCELED");
            if (loseCancelResponse) throw new IllegalStateException("cancel committed, response lost");
        }
    }
}
