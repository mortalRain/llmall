package com.llmall.promotion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.llmall.promotion.mapper.UserVoucherMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class VoucherBindingTransactionTest {
    private AnnotationConfigApplicationContext context;
    private VoucherBindingStore store;
    private JdbcTemplate db;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");return ds;}
        @Bean DataSourceTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean SqlSessionTemplate sql(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);
            MybatisConfiguration cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(UserVoucherMapper.class);f.setConfiguration(cfg);
            return new SqlSessionTemplate(f.getObject());
        }
        @Bean VoucherBindingStore store(SqlSessionTemplate sql){return new VoucherBindingStore(sql.getMapper(UserVoucherMapper.class));}
    }
    @BeforeEach void setup(){
        context=new AnnotationConfigApplicationContext(Config.class);store=context.getBean(VoucherBindingStore.class);db=new JdbcTemplate(context.getBean(DataSource.class));
        db.execute("CREATE TABLE user_voucher(id BIGINT PRIMARY KEY,user_id BIGINT,voucher_id BIGINT,shop_id BIGINT,order_id BIGINT,status INT,receive_time TIMESTAMP,use_time TIMESTAMP,expire_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute("CREATE TABLE voucher_order_cancel(user_voucher_id BIGINT,order_id BIGINT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(user_voucher_id,order_id))");
        db.update("INSERT INTO user_voucher(id,user_id,status) VALUES(1,10,1)");
    }
    @AfterEach void cleanup(){db.execute("DROP ALL OBJECTS");context.close();}
    private int status(){return db.queryForObject("SELECT status FROM user_voucher WHERE id=1",Integer.class);}
    private Long order(){return db.queryForObject("SELECT order_id FROM user_voucher WHERE id=1",Long.class);}
    @Test void lateBindingCannotUndoCommittedCancellation(){
        assertTrue(store.release(1L,10L,100L));
        assertThrows(RuntimeException.class,()->store.bind(1L,10L,100L));
        assertEquals(1,status());
    }
    @Test void retryingOldReleaseCannotReleaseNewOrderBinding(){
        store.bind(1L,10L,100L);store.release(1L,10L,100L);store.bind(1L,10L,200L);
        assertTrue(store.release(1L,10L,100L));
        assertEquals(2,status());assertEquals(200L,order());
    }
    @Test void tombstoneFailureRollsBackRelease(){
        store.bind(1L,10L,100L);
        db.execute("ALTER TABLE voucher_order_cancel ADD CONSTRAINT reject_cancel CHECK(order_id <> 100)");
        assertThrows(RuntimeException.class,()->store.release(1L,10L,100L));assertEquals(2,status());assertEquals(100L,order());
    }
    @Test void expiredUnusedVoucherCanStillBeCanceled(){
        db.update("UPDATE user_voucher SET status=3 WHERE id=1");
        assertTrue(store.release(1L,10L,100L));assertEquals(3,status());
        assertThrows(RuntimeException.class,()->store.bind(1L,10L,100L));
    }
    @Test void anotherUserCannotBindOrRelease(){
        assertThrows(RuntimeException.class,()->store.bind(1L,11L,100L));
        assertThrows(RuntimeException.class,()->store.release(1L,11L,100L));
        assertEquals(1,status());
    }
    @Test void bindingAndReleaseRaceAlwaysEndsUnused() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<?> bind=pool.submit(()->{try{start.await();try{store.bind(1L,10L,100L);}catch(RuntimeException canceled){
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM voucher_order_cancel WHERE order_id=100",Integer.class));
            }}catch(InterruptedException e){throw new RuntimeException(e);}});
            Future<?> release=pool.submit(()->{try{start.await();store.release(1L,10L,100L);}catch(InterruptedException e){throw new RuntimeException(e);}});
            start.countDown();bind.get(15,TimeUnit.SECONDS);release.get(15,TimeUnit.SECONDS);
            assertEquals(1,status());assertThrows(RuntimeException.class,()->store.bind(1L,10L,100L));
        }finally{pool.shutdownNow();}
    }
}
