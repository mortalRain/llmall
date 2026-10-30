package com.llmall.shop;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.common.exception.ConflictException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.shop.mapper.ShopStaffMapper;
import com.llmall.shop.service.impl.ShopStaffServiceImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual InnoDB/MyBatis/Spring proxy test; only a newly created private schema is mutated. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopStaffConcurrencyIntegrationTest {
    private HikariDataSource pool;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private ShopStaffServiceImpl service;
    private ExecutorService workers;
    private final Probe probe = new Probe();
    private final List<String> passed = new ArrayList<>();
    private String schema;
    private Path output;

    @BeforeAll void start() throws Exception {
        output = Paths.get(Objects.requireNonNull(System.getenv("LLMALL_STAFF_TEST_OUTPUT")));
        assertFalse(Files.exists(output), "Use a fresh output directory");
        Files.createDirectories(output);
        schema = "llmall_staff_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertTrue(schema.matches("llmall_staff_test_[0-9a-f]{12}"));
        String password = Objects.requireNonNull(System.getenv("LLMALL_TEST_DB_PASSWORD"));
        Properties auth = new Properties();
        auth.setProperty("user", "root");
        auth.setProperty("password", password);
        try (Connection connection = DriverManager.getConnection(url(""), auth); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        pool = new HikariDataSource();
        pool.setJdbcUrl(url(schema));
        pool.setUsername("root");
        pool.setPassword(password);
        pool.setMaximumPoolSize(3);
        pool.setMinimumIdle(1);
        pool.setConnectionTimeout(3000);
        pool.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        db = new JdbcTemplate(pool);
        db.execute("CREATE TABLE fixture_owner(run_id VARCHAR(64) PRIMARY KEY) ENGINE=InnoDB");
        db.update("INSERT INTO fixture_owner VALUES(?)", schema);
        db.execute("CREATE TABLE shop(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        db.execute("CREATE TABLE shop_staff(id BIGINT PRIMARY KEY AUTO_INCREMENT,shop_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "role VARCHAR(16) NOT NULL,create_time DATETIME DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uk_shop_user(shop_id,user_id)) ENGINE=InnoDB");
        db.update("INSERT INTO shop VALUES(10),(20)");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCacheEnabled(false);
        configuration.addMapper(ShopStaffMapper.class);
        configuration.addInterceptor(probe);
        MybatisSqlSessionFactoryBean builder = new MybatisSqlSessionFactoryBean();
        builder.setConfiguration(configuration);
        builder.setDataSource(pool);
        SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(builder.getObject()));
        ShopStaffServiceImpl target = new ShopStaffServiceImpl();
        ReflectionTestUtils.setField(target, "baseMapper", session.getMapper(ShopStaffMapper.class));
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(pool);
        tx = new TransactionTemplate(transactions);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        service = (ShopStaffServiceImpl) proxy.getProxy();
    }

    @BeforeEach void seed() {
        assertEquals(schema, db.queryForObject("SELECT run_id FROM fixture_owner", String.class));
        db.update("DELETE FROM shop_staff");
        db.update("INSERT INTO shop_staff(shop_id,user_id,role) VALUES(10,1,'OWNER'),(10,2,'OWNER'),(20,1,'OWNER'),(20,2,'STAFF')");
        workers = Executors.newFixedThreadPool(2);
        probe.reset();
    }

    @AfterEach void stopWorkers() throws Exception {
        probe.contender.countDown();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test void concurrentSelfDemotionKeepsOneOwner() throws Exception {
        race(false);
        passed.add("concurrent-self-demotion-keeps-owner");
    }
    @Test void concurrentSelfRemovalKeepsOneOwner() throws Exception {
        race(true);
        passed.add("concurrent-self-removal-keeps-owner");
    }
    private void race(boolean remove) throws Exception {
        probe.racing.set(true);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> requests = new ArrayList<>();
        for (long user : new long[]{1, 2}) requests.add(workers.submit(() -> {
            assertTrue(start.await(3, TimeUnit.SECONDS));
            try {
                if (remove) service.removeStaff(10L, user, user);
                else service.updateStaffRole(10L, user, user, "MANAGER");
                return "ok";
            } catch (ConflictException e) { return "conflict"; }
        }));
        start.countDown();
        List<String> results = List.of(requests.get(0).get(8, TimeUnit.SECONDS), requests.get(1).get(8, TimeUnit.SECONDS));
        assertEquals(1L, owners(10), "Concurrent requests must not remove every owner");
        assertEquals(1, Collections.frequency(results, "ok"));
        assertEquals(1, Collections.frequency(results, "conflict"));
    }

    @Test void revokedManagerCannotUseAnOlderRepeatableReadSnapshot() throws Exception {
        db.update("UPDATE shop_staff SET role='MANAGER' WHERE shop_id=10 AND user_id=2");
        CountDownLatch revoked = new CountDownLatch(1), commit = new CountDownLatch(1);
        Future<?> revoker = workers.submit(() -> tx.execute(status -> {
            db.queryForObject("SELECT id FROM shop WHERE id=10 FOR UPDATE", Long.class);
            db.update("UPDATE shop_staff SET role='STAFF' WHERE shop_id=10 AND user_id=2");
            revoked.countDown();
            await(commit);
            return null;
        }));
        try {
            assertTrue(revoked.await(3, TimeUnit.SECONDS));
            probe.permission.set(true);
            Future<String> request = workers.submit(() -> {
                try {
                    tx.execute(status -> {
                        assertEquals("MANAGER", db.queryForObject("SELECT role FROM shop_staff WHERE shop_id=10 AND user_id=2", String.class));
                        service.addStaff(10L, 2L, 3L, "STAFF");
                        return null;
                    });
                    return "ok";
                } catch (ForbiddenException e) { return "forbidden"; }
            });
            assertTrue(probe.contender.await(3, TimeUnit.SECONDS));
            commit.countDown();
            revoker.get(5, TimeUnit.SECONDS);
            assertEquals("forbidden", request.get(5, TimeUnit.SECONDS));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM shop_staff WHERE shop_id=10 AND user_id=3", Integer.class));
            passed.add("revoked-manager-old-rr-snapshot-rejected");
        } finally { commit.countDown(); }
    }

    @Test void rollbackRestoresRoleAndReleasesLock() throws Exception {
        tx.execute(status -> {
            service.updateStaffRole(10L, 1L, 2L, "MANAGER");
            status.setRollbackOnly();
            return null;
        });
        assertEquals(2L, owners(10));
        workers.submit(() -> service.addStaff(10L, 2L, 3L, "STAFF")).get(5, TimeUnit.SECONDS);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM shop_staff WHERE shop_id=10 AND user_id=3", Integer.class));
        passed.add("rollback-restores-role-and-releases-lock");
    }

    @Test void oldOwnerSnapshotCannotBypassLastOwnerCheck() {
        assertThrows(ConflictException.class, () -> tx.execute(status -> {
            assertEquals(2L, owners(10)); // establish the outer transaction's old snapshot
            assertDoesNotThrow(() -> workers.submit(() -> service.updateStaffRole(10L, 2L, 2L, "MANAGER"))
                    .get(5, TimeUnit.SECONDS));
            service.updateStaffRole(10L, 1L, 1L, "MANAGER");
            return null;
        }));
        assertEquals(1L, owners(10));
        passed.add("old-owner-count-snapshot-cannot-bypass-last-owner");
    }

    @Test void anotherShopCanChangeWhileFirstShopIsLocked() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> first = workers.submit(() -> tx.execute(status -> {
            service.updateStaffRole(10L, 1L, 2L, "MANAGER");
            locked.countDown();
            await(release);
            return null;
        }));
        try {
            assertTrue(locked.await(3, TimeUnit.SECONDS));
            workers.submit(() -> service.updateStaffRole(20L, 1L, 2L, "MANAGER")).get(3, TimeUnit.SECONDS);
            assertFalse(first.isDone());
            passed.add("different-shop-not-globally-blocked");
        } finally { release.countDown(); first.get(5, TimeUnit.SECONDS); }
    }

    @AfterAll void close() throws Exception {
        if (pool != null) pool.close();
        if (output != null) new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),
                Map.of("schema", schema, "passed", passed, "completed", passed.size() == 6, "isolation", "REPEATABLE_READ"));
    }
    private long owners(long shop) {
        return Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM shop_staff WHERE shop_id=? AND role='OWNER'", Long.class, shop));
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private static String url(String schema) {
        return "jdbc:mysql://127.0.0.1:3307/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectTimeout=3000&socketTimeout=5000";
    }

    @Intercepts({@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})})
    static class Probe implements Interceptor {
        final AtomicBoolean racing = new AtomicBoolean(), permission = new AtomicBoolean();
        final AtomicInteger locks = new AtomicInteger(), writes = new AtomicInteger();
        volatile CountDownLatch contender = new CountDownLatch(1);
        void reset() { racing.set(false); permission.set(false); locks.set(0); writes.set(0); contender = new CountDownLatch(1); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String name = ((MappedStatement) invocation.getArgs()[0]).getId();
            boolean parent = name.endsWith(".lockShopForStaffChange");
            if (permission.get() && (parent || name.endsWith(".selectList"))) contender.countDown();
            if (racing.get()) {
                if (parent && locks.incrementAndGet() == 2) contender.countDown();
                if (invocation.getMethod().getName().equals("update")) {
                    if (writes.incrementAndGet() == 2) contender.countDown();
                    await(contender);
                }
            }
            return invocation.proceed();
        }
    }
}
