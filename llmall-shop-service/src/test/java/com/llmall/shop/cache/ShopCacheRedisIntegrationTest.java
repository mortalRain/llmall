package com.llmall.shop.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.dto.ShopDTO;
import com.llmall.shop.domain.bo.RedisData;
import com.llmall.shop.mapper.*;
import com.llmall.shop.mq.ShopEventPublisher;
import com.llmall.shop.service.impl.ShopServiceImpl;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.resource.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Redis scripts + private MySQL fixture. Host stops only the marked private Redis for the final fault. */
@Tag("integration")
class ShopCacheRedisIntegrationTest {
    @Test void realScriptsTransactionsAndBackendFailure() throws Exception {
        String runId=Objects.requireNonNull(System.getenv("LLMALL_CACHE_RUN_ID"));
        int port=Integer.parseInt(Objects.requireNonNull(System.getenv("LLMALL_CACHE_REDIS_PORT")));
        assertTrue(port>1024&&port!=6379&&port<65536,"Never point this test at the business Redis");
        Path output=Paths.get(Objects.requireNonNull(System.getenv("LLMALL_CACHE_OUTPUT")));
        assertFalse(Files.exists(output),"Use a fresh result directory");Files.createDirectories(output);
        Map<String,Object> report=new LinkedHashMap<>();List<String> cases=new ArrayList<>();
        report.put("runId",runId);report.put("redisPort",port);report.put("cases",cases);
        Logger root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);Level previous=root.getLevel();root.setLevel(Level.WARN);
        DefaultClientResources resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        LettuceConnectionFactory factory=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",port),
                LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofMillis(500)).shutdownTimeout(Duration.ofMillis(100)).build());
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            requireMemory();factory.afterPropertiesSet();StringRedisTemplate redis=new StringRedisTemplate(factory);
            assertEquals(runId,redis.opsForValue().get("fixture:owner"),"Private Redis ownership mismatch; stop before any cache writes");
            report.put("redisOwnerVerified",true);ObjectMapper json=new ObjectMapper().findAndRegisterModules();
            String schema="llmall_cache_test_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            assertTrue(schema.matches("llmall_cache_test_[0-9a-f]{12}"));report.put("schema",schema);
            String password=Objects.requireNonNull(System.getenv("LLMALL_TEST_DB_PASSWORD"));
            String base="jdbc:mysql://127.0.0.1:3307/",options="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectTimeout=3000&socketTimeout=3000";
            Properties auth=new Properties();auth.setProperty("user","root");auth.setProperty("password",password);
            try(Connection c=DriverManager.getConnection(base+options,auth);Statement s=c.createStatement()) {s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");}
            try(HikariDataSource pool=new HikariDataSource()) {
                pool.setJdbcUrl(base+schema+options);pool.setUsername("root");pool.setPassword(password);pool.setMaximumPoolSize(2);pool.setMinimumIdle(1);pool.setConnectionTimeout(3000);
                JdbcTemplate db=new JdbcTemplate(pool);db.execute("CREATE TABLE fixture_owner(run_id VARCHAR(64) PRIMARY KEY)");db.update("INSERT INTO fixture_owner VALUES(?)",runId);
                db.execute("CREATE TABLE shop_detail_fixture(id BIGINT PRIMARY KEY,name VARCHAR(64) NOT NULL)");
                db.update("INSERT INTO shop_detail_fixture VALUES(1,'old'),(2,'other')");
                ShopDetailCache cache=new ShopDetailCache(redis,json,Runnable::run,new ShopCacheFallback(4,500));
                ShopServiceImpl service=new ShopServiceImpl(redis,cache,mock(ConsumeRecordMapper.class),mock(ShopTypeMapper.class),mock(ShopEventPublisher.class));
                TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(pool));

                AtomicInteger reads=new AtomicInteger();Supplier<ShopDTO> source=()->{reads.incrementAndGet();return read(db,1);};
                assertEquals("old",cache.get(1L,source).getName());
                long ttl=Objects.requireNonNull(redis.getExpire("cache:shop:1"));assertTrue(ttl>1800&&ttl<=2700);
                long before=gets(redis);for(int i=0;i<100;i++)assertEquals("old",cache.get(1L,source).getName());
                assertEquals(100,gets(redis)-before);assertEquals(1,reads.get());cases.add("hot-100-requests-100-redis-get-0-extra-db");
                assertNull(cache.get(9999L,()->read(db,9999)));long negativeTtl=Objects.requireNonNull(redis.getExpire("cache:shop:9999"));
                assertTrue(negativeTtl>0&&negativeTtl<=180);assertNull(cache.get(9999L,()->{fail("negative-cache database read");return null;}));
                cases.add("positive-and-negative-physical-ttl");

                cache.invalidate(1L);
                CountDownLatch oldRead=new CountDownLatch(1),releaseOld=new CountDownLatch(1),newRead=new CountDownLatch(1),releaseNew=new CountDownLatch(1);
                try {
                    Future<ShopDTO> old=workers.submit(()->cache.get(1L,()->{ShopDTO d=read(db,1);oldRead.countDown();await(releaseOld);return d;}));
                    assertTrue(oldRead.await(3,TimeUnit.SECONDS));String oldToken=redis.opsForValue().get("lock:shop:1");assertNotNull(oldToken);
                    tx.execute(status->{db.update("UPDATE shop_detail_fixture SET name='new' WHERE id=1");invalidateAfterCommit(service,1);
                        assertEquals(oldToken,redis.opsForValue().get("lock:shop:1"));return null;});
                    assertNull(redis.opsForValue().get("lock:shop:1"));assertNull(redis.opsForValue().get("cache:shop:1"));
                    Future<ShopDTO> latest=workers.submit(()->cache.get(1L,()->{ShopDTO d=read(db,1);newRead.countDown();await(releaseNew);return d;}));
                    assertTrue(newRead.await(3,TimeUnit.SECONDS));String newToken=redis.opsForValue().get("lock:shop:1");assertNotNull(newToken);assertNotEquals(oldToken,newToken);
                    releaseOld.countDown();assertEquals("old",old.get(3,TimeUnit.SECONDS).getName());
                    assertEquals(newToken,redis.opsForValue().get("lock:shop:1"));assertNull(redis.opsForValue().get("cache:shop:1"));
                    releaseNew.countDown();assertEquals("new",latest.get(3,TimeUnit.SECONDS).getName());assertEquals("new",name(redis,json,1));
                    cases.add("commit-revokes-old-publisher-and-old-unlock-does-not-delete-new-lock");
                } finally {releaseOld.countDown();releaseNew.countDown();}

                cache.invalidate(1L);CountDownLatch leaseRead=new CountDownLatch(1),releaseLease=new CountDownLatch(1);
                try {
                    Future<ShopDTO> old=workers.submit(()->cache.get(1L,()->{ShopDTO d=read(db,1);leaseRead.countDown();await(releaseLease);return d;}));
                    assertTrue(leaseRead.await(3,TimeUnit.SECONDS));redis.expire("lock:shop:1",Duration.ofMillis(1));Thread.sleep(10);
                    db.update("UPDATE shop_detail_fixture SET name='lease-new' WHERE id=1");
                    redis.opsForValue().set("lock:shop:1","new-lease-owner",Duration.ofSeconds(30));
                    redis.opsForValue().set("cache:shop:1",payload(json,read(db,1),false),Duration.ofMinutes(40));
                    releaseLease.countDown();assertEquals("new",old.get(3,TimeUnit.SECONDS).getName());
                    assertEquals("lease-new",name(redis,json,1));assertEquals("new-lease-owner",redis.opsForValue().get("lock:shop:1"));
                    cases.add("expired-owner-cannot-overwrite-cache-or-unlock-new-owner");
                } finally {releaseLease.countDown();}

                CountDownLatch nullRead=new CountDownLatch(1),releaseNull=new CountDownLatch(1);
                try {
                    Future<ShopDTO> missing=workers.submit(()->cache.get(3L,()->{ShopDTO d=read(db,3);nullRead.countDown();await(releaseNull);return d;}));
                    assertTrue(nullRead.await(3,TimeUnit.SECONDS));
                    tx.execute(status->{db.update("INSERT INTO shop_detail_fixture VALUES(3,'created')");invalidateAfterCommit(service,3);return null;});
                    assertEquals("created",cache.get(3L,()->read(db,3)).getName());releaseNull.countDown();assertNull(missing.get(3,TimeUnit.SECONDS));
                    assertEquals("created",name(redis,json,3));cases.add("late-negative-sentinel-cannot-hide-newly-created-row");
                } finally {releaseNull.countDown();}

                cache.invalidate(1L);cache.get(1L,()->read(db,1));String cached=redis.opsForValue().get("cache:shop:1");
                redis.opsForValue().set("lock:shop:1","rollback-owner",Duration.ofSeconds(30));
                tx.execute(status->{db.update("UPDATE shop_detail_fixture SET name='rolled-back' WHERE id=1");invalidateAfterCommit(service,1);
                    assertEquals(cached,redis.opsForValue().get("cache:shop:1"));status.setRollbackOnly();return null;});
                assertEquals("lease-new",read(db,1).getName());assertEquals(cached,redis.opsForValue().get("cache:shop:1"));
                assertEquals("rollback-owner",redis.opsForValue().get("lock:shop:1"));cases.add("rollback-keeps-data-cache-and-lease");

                cache.invalidate(1L);redis.opsForValue().set("cache:shop:1",payload(json,read(db,1),true),Duration.ofMinutes(40));
                List<Runnable> queue=new ArrayList<>();ShopDetailCache queued=new ShopDetailCache(redis,json,queue::add,new ShopCacheFallback(4,500));
                assertEquals("lease-new",queued.get(1L,()->{fail("expired queued source");return null;}).getName());
                redis.expire("lock:shop:1",Duration.ofMillis(1));Thread.sleep(10);queue.get(0).run();
                assertNull(redis.opsForValue().get("lock:shop:1"));cases.add("expired-queued-job-skips-database");
                ShopDetailCache rejected=new ShopDetailCache(redis,json,task->{throw new RejectedExecutionException("fixture full");},new ShopCacheFallback(4,500));
                assertEquals("lease-new",rejected.get(1L,()->{fail("rejected source");return null;}).getName());
                assertNull(redis.opsForValue().get("lock:shop:1"));cases.add("rejected-job-releases-real-lease-and-serves-stale");

                // The host verifies the container label and stops only this private instance, then acknowledges.
                report.put("beforeOutagePassed",true);json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("before-outage.json").toFile(),report);
                Files.writeString(output.resolve("request-outage"),runId);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
                while(!Files.exists(output.resolve("outage-ready"))&&System.nanoTime()<until)Thread.sleep(50);
                assertTrue(Files.exists(output.resolve("outage-ready")),"Host must stop only the private Redis and acknowledge");
                AtomicInteger outageReads=new AtomicInteger();long at=System.nanoTime();
                assertEquals("other",cache.get(2L,()->{outageReads.incrementAndGet();return read(db,2);}).getName());
                report.put("singleOutageReadMillis",(System.nanoTime()-at)/1e6);assertEquals(1,outageReads.get());
                cases.add("real-redis-stop-falls-back-to-private-mysql-once");report.put("completed",true);
            }
        } catch(Exception|AssertionError e) {
            report.put("completed",false);report.put("failure",e.getClass().getSimpleName());report.put("failureMessage",e.getMessage());throw e;
        } finally {
            workers.shutdownNow();
            try {workers.awaitTermination(3,TimeUnit.SECONDS);}
            finally {try {factory.destroy();}finally {try {resources.shutdown(0,100,TimeUnit.MILLISECONDS).get(3,TimeUnit.SECONDS);}
                finally {root.setLevel(previous);json().writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),report);}}}
        }
    }
    static ShopDTO read(JdbcTemplate db,long id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT id,name FROM shop_detail_fixture WHERE id=?",id);
        if(rows.isEmpty())return null;ShopDTO d=new ShopDTO();d.setId(((Number)rows.get(0).get("id")).longValue());d.setName((String)rows.get(0).get("name"));return d;
    }
    static String payload(ObjectMapper json,ShopDTO dto,boolean stale) throws Exception {
        return json.writeValueAsString(RedisData.of(dto,LocalDateTime.now().plusMinutes(stale?-1:30)));
    }
    static String name(StringRedisTemplate redis,ObjectMapper json,long id) throws Exception {
        return json.readTree(Objects.requireNonNull(redis.opsForValue().get("cache:shop:"+id))).path("data").path("name").asText();
    }
    static long gets(StringRedisTemplate redis) {
        try(RedisConnection c=Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
            String value=Objects.requireNonNull(c.info("commandstats")).getProperty("cmdstat_get");
            return Long.parseLong(value.substring("calls=".length(),value.indexOf(',')));
        }
    }
    static void invalidateAfterCommit(ShopServiceImpl service,long id){ReflectionTestUtils.invokeMethod(service,"evictCacheAfterCommit",id);}
    static void await(CountDownLatch latch) {try {assertTrue(latch.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void requireMemory(){assertTrue(((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getFreePhysicalMemorySize()>=1073741824L,"Host free memory below 1GiB");}
    static ObjectMapper json(){return new ObjectMapper();}
}
