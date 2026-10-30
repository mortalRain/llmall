package com.llmall.search.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.client.ShopClient;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.search.domain.po.ShopSearchDoc;
import com.llmall.search.es.*;
import com.llmall.search.event.ShopDocChangedEvent;
import com.llmall.search.mapper.*;
import com.llmall.search.service.impl.SearchServiceImpl;
import com.llmall.search.support.SearchSyncSchemaGuard;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.http.HttpHost;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.elasticsearch.client.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("integration") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchSyncIntegrationTest {
    static final long BASE = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    HikariDataSource pool; JdbcTemplate db; SearchServiceImpl service; RestHighLevelClient client;
    EsShopSearchGatewayImpl gateway; ElasticsearchRestTemplate real; IndexCoordinates coordinates;
    String schema, index; Path output; ExecutorService workers;
    final Probe probe = new Probe(); final List<String> passed = new ArrayList<>();
    final EsShopSearchGateway delivery = mock(EsShopSearchGateway.class);
    @BeforeAll void setup() throws Exception {
        output=Paths.get(Objects.requireNonNull(System.getenv("LLMALL_SYNC_TEST_OUTPUT")));
        assertFalse(Files.exists(output)); Files.createDirectories(output);
        schema="llmall_sync_test_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        assertTrue(schema.matches("llmall_sync_test_[0-9a-f]{12}"));
        Properties auth=new Properties(); auth.setProperty("user","root");
        auth.setProperty("password",Objects.requireNonNull(System.getenv("LLMALL_TEST_DB_PASSWORD")));
        try(Connection c=DriverManager.getConnection(url(""),auth); Statement s=c.createStatement()) {s.execute("CREATE DATABASE `"+schema+"`");}
        pool=new HikariDataSource(); pool.setJdbcUrl(url(schema)); pool.setUsername("root"); pool.setPassword(auth.getProperty("password"));
        pool.setMaximumPoolSize(3); pool.setMinimumIdle(1); pool.setConnectionTimeout(3000);
        db=new JdbcTemplate(pool); db.execute("CREATE TABLE fixture_owner(id VARCHAR(64) PRIMARY KEY)"); db.update("INSERT INTO fixture_owner VALUES(?)",schema);
        db.execute("CREATE TABLE shop_search_doc(shop_id BIGINT PRIMARY KEY,name VARCHAR(64) NOT NULL,type_id BIGINT NOT NULL,"
                +"type_name VARCHAR(64),area VARCHAR(64),address VARCHAR(64) NOT NULL,x DOUBLE,y DOUBLE,avg_price BIGINT,score INT NOT NULL DEFAULT 0,"
                +"comments INT NOT NULL DEFAULT 0,sold INT NOT NULL DEFAULT 0,hot_score DOUBLE NOT NULL DEFAULT 0,status INT NOT NULL DEFAULT 1,"
                +"event_time DATETIME(3),update_time DATETIME) ENGINE=InnoDB");
        MybatisConfiguration config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);config.setCacheEnabled(false);
        config.addMapper(ShopSearchDocMapper.class);config.addInterceptor(probe);
        MybatisSqlSessionFactoryBean builder=new MybatisSqlSessionFactoryBean();builder.setDataSource(pool);builder.setConfiguration(config);
        ShopSearchDocMapper docs=new SqlSessionTemplate(Objects.requireNonNull(builder.getObject())).getMapper(ShopSearchDocMapper.class);
        SearchServiceImpl target=new SearchServiceImpl(docs,mock(SearchHotWordMapper.class),mock(SearchHistoryMapper.class),
                mock(ConsumeRecordMapper.class),mock(StringRedisTemplate.class),mock(ShopClient.class),delivery,
                new ShopSearchEngineProperties(),mock(BizMetrics.class));
        TransactionInterceptor tx=new TransactionInterceptor();tx.setTransactionManager(new DataSourceTransactionManager(pool));
        tx.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(tx);service=(SearchServiceImpl)proxy.getProxy();
        client=new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1",9200,"http"))
                .setRequestConfigCallback(b->b.setConnectTimeout(1000).setSocketTimeout(3000))
                .setHttpClientConfigCallback(b->b.setDefaultIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(2).build()).setMaxConnTotal(2).setMaxConnPerRoute(2)));
        index="llmall_sync_test_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        assertTrue(index.matches("llmall_sync_test_[0-9a-f]{12}")); coordinates=IndexCoordinates.of(index);
        Request create=new Request("PUT","/"+index);create.setJsonEntity("{\"mappings\":{\"_meta\":{\"owner\":\""+schema+"\"},\"properties\":{\"eventTimestamp\":{\"type\":\"date\",\"format\":\"epoch_millis\"}}}}");
        client.getLowLevelClient().performRequest(create);real=new ElasticsearchRestTemplate(client);
        ElasticsearchOperations redirected=mock(ElasticsearchOperations.class);
        when(redirected.getElasticsearchConverter()).thenReturn(real.getElasticsearchConverter());
        when(redirected.getIndexCoordinatesFor(ShopSearchDocument.class)).thenReturn(coordinates);
        when(redirected.save(any(ShopSearchDocument.class))).thenAnswer(c->real.save((ShopSearchDocument)c.getArgument(0),coordinates));
        when(redirected.save(any(Iterable.class))).thenAnswer(c->real.save((Iterable<ShopSearchDocument>)c.getArgument(0),coordinates));
        when(redirected.update(any(UpdateQuery.class),eq(coordinates))).thenAnswer(c->real.update(c.getArgument(0),coordinates));
        doAnswer(c->{real.bulkUpdate(c.getArgument(0),coordinates);return null;}).when(redirected).bulkUpdate(anyList(),eq(coordinates));
        gateway=new EsShopSearchGatewayImpl(redirected,docs,new ShopSearchEngineProperties());
    }
    @BeforeEach void reset() {
        assertEquals(schema,db.queryForObject("SELECT id FROM fixture_owner",String.class));
        db.update("DELETE FROM shop_search_doc");
        db.update("INSERT INTO shop_search_doc(shop_id,name,type_id,address,event_time) VALUES(1,'seed',1,'fixture',?)",time(BASE));
        workers=Executors.newFixedThreadPool(2);probe.reset();
        org.mockito.Mockito.reset(delivery);
    }
    @AfterEach void closeWorkers() throws Exception {probe.release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    @Test void laterMillisecondWinsWithinOneSecond() {
        service.applyShopHotChanged(event(1,"new",BASE+900));service.applyShopHotChanged(event(1,"old",BASE+100));
        assertEquals("new",name());
        assertEquals(time(BASE+900),db.queryForObject("SELECT event_time FROM shop_search_doc WHERE shop_id=1",LocalDateTime.class));
        passed.add("same-second-preserves-milliseconds");
    }
    @Test void staleInitialReadCannotOverwriteAConcurrentCommit() throws Exception {
        Future<?> old=workers.submit(()->{probe.old.set(Thread.currentThread());service.applyShopHotChanged(event(1,"old",BASE+1000));});
        try {
            assertTrue(probe.read.await(3,TimeUnit.SECONDS));
            workers.submit(()->service.applyShopHotChanged(event(1,"new",BASE+2000))).get(5,TimeUnit.SECONDS);
            probe.release.countDown();old.get(5,TimeUnit.SECONDS);assertEquals("new",name());
            passed.add("current-read-rejects-stale-snapshot");
        } finally {probe.release.countDown();}
    }
    @Test void concurrentFirstInsertConvergesWithoutDuplicateFailure() throws Exception {
        db.update("DELETE FROM shop_search_doc");probe.creating.set(true);
        Future<?> a=workers.submit(()->service.applyShopHotChanged(event(1,"old",BASE+1000)));
        Future<?> b=workers.submit(()->service.applyShopHotChanged(event(1,"new",BASE+2000)));
        a.get(8,TimeUnit.SECONDS);b.get(8,TimeUnit.SECONDS);assertEquals("new",name());
        passed.add("concurrent-first-insert-converges");
    }
    @Test void equalTimestampDoesNotReplaceCommittedSnapshot() {
        service.applyShopHotChanged(event(1,"first",BASE+1000));service.applyShopHotChanged(event(1,"conflicting-equal",BASE+1000));
        assertEquals("first",name());passed.add("equal-event-time-first-committed-wins");
    }
    @Test void rolledBackProjectionDoesNotPublishToEs() {
        when(delivery.enabled()).thenReturn(true);
        new TransactionTemplate(new DataSourceTransactionManager(pool)).executeWithoutResult(status -> {
            service.applyShopHotChanged(event(1,"rolled-back",BASE+1000));
            status.setRollbackOnly();
        });
        verify(delivery,never()).saveDocument(any());
        assertEquals("seed",name());passed.add("rollback-does-not-publish-es");
    }
    @Test void committedProjectionActuallyReachesPrivateEsWithSameMilliseconds() {
        when(delivery.enabled()).thenReturn(true);
        doAnswer(c->{gateway.saveDocument(c.getArgument(0));return null;}).when(delivery).saveDocument(any());
        service.applyShopHotChanged(event(1,"committed-new",BASE+900));
        service.applyShopHotChanged(new ShopDocChangedEvent().setShopId(1L).setSold(99).setOccurredAt(BASE+1200));
        service.applyShopHotChanged(event(1,"late-old",BASE+100));
        ShopSearchDocument stored=real.get("1",ShopSearchDocument.class,coordinates);
        assertEquals("committed-new",stored.getName());assertEquals(99,stored.getSold());assertEquals(BASE+1200,stored.getEventTimestamp());
        assertEquals("committed-new",name());passed.add("committed-mysql-to-es-preserves-milliseconds");
    }
    @Test void lateSingleEsWriteCannotReplaceNewerDocument() {
        gateway.saveDocument(doc(11,"new",BASE+2000));gateway.saveDocument(doc(11,"old",BASE+1000));
        assertEquals("new",real.get("11",ShopSearchDocument.class,coordinates).getName());
        assertEquals(11L,real.get("11",ShopSearchDocument.class,coordinates).getShopId());
        gateway.saveDocument(new ShopSearchDocument().setShopId(11L).setName("unversioned-old"));
        assertEquals("new",real.get("11",ShopSearchDocument.class,coordinates).getName());
        passed.add("es-late-single-write-noop");
    }
    @Test void bulkOldSnapshotCannotUndoNewerEsWrite() {
        gateway.saveDocument(doc(12,"new",BASE+2000));
        gateway.saveDocuments(List.of(doc(12,"old",BASE+1000),doc(13,"created",BASE+3000)));
        assertEquals("new",real.get("12",ShopSearchDocument.class,coordinates).getName());
        assertEquals("created",real.get("13",ShopSearchDocument.class,coordinates).getName());
        passed.add("es-bulk-old-snapshot-noop-and-upsert");
    }
    @Test void actualMigrationPrecisionIsRequiredAndAccepted() throws Exception {
        SearchSyncSchemaGuard guard=new SearchSyncSchemaGuard(db);
        db.execute("ALTER TABLE shop_search_doc MODIFY event_time DATETIME");
        assertThrows(IllegalStateException.class,guard::afterSingletonsInstantiated);
        Path root=Paths.get(System.getProperty("maven.multiModuleProjectDirectory"));
        String migration=Files.readString(root.resolve("sql/merged/66-search-event-precision.sql"));
        String alter=migration.substring(migration.indexOf("ALTER TABLE"));
        assertTrue(alter.matches("(?s)^ALTER TABLE `shop_search_doc`\\s+MODIFY COLUMN `event_time` DATETIME\\(3\\) DEFAULT NULL COMMENT '[^']*';\\s*$"));
        db.execute(alter); // only current private schema; never execute the script's USE business_db
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
        passed.add("actual-private-migration-and-metadata-gate");
    }
    String name(){return db.queryForObject("SELECT name FROM shop_search_doc WHERE shop_id=1",String.class);}
    static ShopDocChangedEvent event(long id,String name,long at){return new ShopDocChangedEvent().setShopId(id).setName(name).setAddress("fixture").setTypeId(1L).setOccurredAt(at);}
    static ShopSearchDocument doc(long id,String name,long at){return new ShopSearchDocument().setShopId(id).setName(name).setEventTimestamp(at);}
    static LocalDateTime time(long epoch){return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch),ZoneId.systemDefault());}
    static String url(String schema){return "jdbc:mysql://127.0.0.1:3307/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000";}
    @AfterAll void close() throws Exception {
        try {if(client!=null)client.close();}finally{if(pool!=null)pool.close();}
        if(output!=null)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),
                Map.of("schema",schema,"index",index,"passed",passed,"completed",passed.size()==9));
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class Probe implements Interceptor {
        final AtomicReference<Thread> old=new AtomicReference<>();final AtomicBoolean paused=new AtomicBoolean(),creating=new AtomicBoolean();
        final AtomicInteger initialReads=new AtomicInteger();volatile CountDownLatch read,release;volatile CyclicBarrier barrier;
        void reset(){old.set(null);paused.set(false);creating.set(false);initialReads.set(0);read=new CountDownLatch(1);release=new CountDownLatch(1);barrier=new CyclicBarrier(2);}
        @Override public Object intercept(Invocation invocation)throws Throwable {
            Object result=invocation.proceed();
            if(((MappedStatement)invocation.getArgs()[0]).getId().endsWith(".selectById")) {
                if(creating.get()&&initialReads.incrementAndGet()<=2)barrier.await(3,TimeUnit.SECONDS);
                if(Thread.currentThread()==old.get()&&paused.compareAndSet(false,true)){read.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));}
            }
            return result;
        }
    }
}
