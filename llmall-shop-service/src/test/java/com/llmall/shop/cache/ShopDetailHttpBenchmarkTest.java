package com.llmall.shop.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.common.advice.CommonExceptionAdvice;
import com.llmall.shop.controller.ShopController;
import com.llmall.shop.domain.bo.RedisData;
import com.llmall.api.dto.ShopDTO;
import com.llmall.shop.config.ShopCacheConfig;
import com.llmall.shop.mapper.*;
import com.llmall.shop.mq.ShopEventPublisher;
import com.llmall.shop.service.*;
import com.llmall.shop.service.impl.ShopServiceImpl;
import com.llmall.shop.task.*;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.resource.DefaultClientResources;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Opt-in component HTTP measurement. Real SQL/Redis, no gateway/auth/Nacos/MQ deployment. */
@Tag("integration")
class ShopDetailHttpBenchmarkTest {
    @Configuration @EnableWebMvc static class MvcConfig { }

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<Map<String, Object>> phases = new ArrayList<>();
    private final ExecutorService clients = Executors.newFixedThreadPool(8);
    private final QueryProbe probe = new QueryProbe();
    private BufferedWriter samples;
    private URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void measureRealShopDetailsAndBoundedFailurePaths() throws Exception {
        String runId = Objects.requireNonNull(System.getenv("LLMALL_CACHE_RUN_ID"));
        int port = Integer.parseInt(Objects.requireNonNull(System.getenv("LLMALL_CACHE_REDIS_PORT")));
        assertTrue(port > 1024 && port < 65536 && port != 6379);
        Path output = Paths.get(Objects.requireNonNull(System.getenv("LLMALL_SHOP_HTTP_OUTPUT")));
        assertFalse(Files.exists(output), "Use a fresh output directory");
        requireMemory();
        Files.createDirectories(output);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("scope", "current component HTTP only; actual controller/service/cache/MyBatis/MySQL/Redis; no old-version comparison");
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("fixtureRows", 1000);
        report.put("httpMaxThreads", 8);
        report.put("redisCommandTimeoutMs", 100);
        report.put("phases", phases);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        root.setLevel(Level.WARN);
        DefaultClientResources resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port),
                LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofMillis(100))
                        .shutdownTimeout(Duration.ofMillis(100)).build());
        ThreadPoolTaskExecutor rebuilds = (ThreadPoolTaskExecutor) new ShopCacheConfig().cacheRebuildExecutor();
        AtomicBoolean reject = new AtomicBoolean();
        Tomcat server = null;
        try (HikariDataSource pool = new HikariDataSource()) {
            factory.afterPropertiesSet();
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            assertEquals(runId, redis.opsForValue().get("fixture:owner"), "Private Redis ownership mismatch");
            report.put("redisOwnerVerified", true);
            String schema = createSchema();
            report.put("schema", schema);
            pool.setJdbcUrl(url(schema));
            pool.setUsername("root");
            pool.setPassword(Objects.requireNonNull(System.getenv("LLMALL_TEST_DB_PASSWORD")));
            pool.setMaximumPoolSize(4);
            pool.setMinimumIdle(1);
            pool.setConnectionTimeout(3000);
            seed(new JdbcTemplate(pool), runId);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setCacheEnabled(false);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.addMapper(ShopMapper.class);
            configuration.addMapper(ShopTypeMapper.class);
            configuration.addInterceptor(probe);
            MybatisSqlSessionFactoryBean builder = new MybatisSqlSessionFactoryBean();
            builder.setDataSource(pool);
            builder.setConfiguration(configuration);
            SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(builder.getObject()));
            ShopDetailCache cache = new ShopDetailCache(redis, json, task -> {
                if (reject.get()) throw new RejectedExecutionException("controlled fixture rejection");
                rebuilds.execute(task);
            }, new ShopCacheFallback(4, 500));
            ShopServiceImpl service = new ShopServiceImpl(redis, cache, mock(ConsumeRecordMapper.class),
                    session.getMapper(ShopTypeMapper.class), mock(ShopEventPublisher.class));
            ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(ShopMapper.class));
            ShopController controller = new ShopController(service, mock(IShopStaffService.class), mock(IShopFavoriteService.class),
                    mock(ShopSearchReconcileTask.class), mock(ShopStatRollupTask.class));
            server = server(controller);
            base = URI.create("http://127.0.0.1:" + server.getConnector().getLocalPort() + "/shops/");
            report.put("port", server.getConnector().getLocalPort());
            try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("requests.csv"))) {
                samples = writer;
                samples.write("phase,sequence,status,latencyNs,expectedStatus\n");
                request("setup", 0, 1, 200, false);
                assertTrue(probe.shops.get() > 0, "Actual mapper must read MySQL during initial fill");
                assertEquals(1, probe.types.get(), "Real type dictionary read");
                for (int round = 1; round <= 3; round++) {
                    for (int i = 0; i < 300; i++) {
                        if (i % 100 == 0) requireMemory();
                        request("warmup", i, 1, 200, false);
                    }
                    phase("hot-" + round, 4, 2000, 1, 200);
                    assertEquals(0, probe.shops.get());
                    assertEquals(0, probe.types.get());
                }

                cache.invalidate(1L);
                Gate cold = new Gate(1);
                probe.gate.set(cold);
                try {
                    CompletableFuture<Void> run = CompletableFuture.runAsync(() -> uncheckedPhase("cold-burst", 8, 8, 1, 200));
                    assertTrue(cold.entered.await(3, TimeUnit.SECONDS));
                    Thread.sleep(100); // deliberate overlap fixture, excluded from hot metrics
                    cold.release.countDown();
                    run.get(10, TimeUnit.SECONDS);
                    assertEquals(1, probe.shops.get(), "One SQL for the cold same-key burst");
                } finally { cold.release.countDown(); probe.gate.set(null); }

                ShopDTO stale = service.queryById(1L);
                stale.setName("stale fixture");
                redis.opsForValue().set("cache:shop:1", json.writeValueAsString(
                        RedisData.of(stale, LocalDateTime.now().minusMinutes(1))), Duration.ofMinutes(3));
                Gate refresh = new Gate(1);
                probe.gate.set(refresh);
                try {
                    phase("stale-while-rebuild-blocked", 8, 8, 1, 200);
                    assertTrue(refresh.entered.await(3, TimeUnit.SECONDS));
                    assertEquals(1, refresh.release.getCount(), "All stale HTTP responses finish before DB gate opens");
                    assertEquals(1, probe.shops.get());
                    phases.get(phases.size() - 1).put("shopReadsStarted", probe.shops.get());
                    phases.get(phases.size() - 1).put("responsesCompletedBeforeRebuildRelease", true);
                } finally {
                    refresh.release.countDown();
                    probe.gate.set(null);
                }
                awaitRebuild(rebuilds, redis);
                reject.set(true);
                redis.opsForValue().set("cache:shop:1", json.writeValueAsString(
                        RedisData.of(stale, LocalDateTime.now().minusMinutes(1))), Duration.ofMinutes(3));
                phase("controlled-rejection-stale", 8, 8, 1, 200);
                assertEquals(0, probe.shops.get());
                assertFalse(Boolean.TRUE.equals(redis.hasKey("lock:shop:1")));
                reject.set(false);

                // Host verifies the private container ID/label before stopping it. Never stop business Redis.
                samples.flush();
                Files.writeString(output.resolve("request-outage"), runId);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!Files.exists(output.resolve("outage-ready")) && System.nanoTime() < deadline) Thread.sleep(50);
                assertTrue(Files.exists(output.resolve("outage-ready")), "Host must stop the private Redis");
                assertEquals(runId, Files.readString(output.resolve("outage-ready")).trim());

                Gate outage = new Gate(1);
                probe.gate.set(outage);
                try {
                    CompletableFuture<Void> run = CompletableFuture.runAsync(() -> uncheckedPhase("redis-down-same-key", 8, 8, 1, 200));
                    assertTrue(outage.entered.await(3, TimeUnit.SECONDS));
                    Thread.sleep(150); // hold actual SQL to expose overlapping fallback requests
                    outage.release.countDown();
                    run.get(10, TimeUnit.SECONDS);
                    assertEquals(1, probe.shops.get(), "Same-key outage calls share one SQL");
                } finally { outage.release.countDown(); probe.gate.set(null); }

                Gate occupied = new Gate(4);
                probe.gate.set(occupied);
                List<Future<Sample>> owners = new ArrayList<>();
                probe.reset();
                try {
                    for (int i = 0; i < 4; i++) {
                        final int slot = i;
                        owners.add(clients.submit(() -> request("redis-down-capacity", slot, 10 + slot, 200, true)));
                    }
                    assertTrue(occupied.entered.await(3, TimeUnit.SECONDS));
                    Sample refusal = request("redis-down-capacity", 4, 20, 503, true);
                    assertEquals(4, probe.shops.get(), "Fifth key must not execute SQL");
                    occupied.release.countDown();
                    List<Sample> capacity = new ArrayList<>();
                    capacity.add(refusal);
                    for (Future<Sample> owner : owners) capacity.add(owner.get(10, TimeUnit.SECONDS));
                    summarize("redis-down-capacity", capacity, 0, 5);
                } finally { occupied.release.countDown(); probe.gate.set(null); }
                samples.flush();
            }
            report.put("completed", true);
        } catch (Exception | AssertionError e) {
            report.put("completed", false);
            report.put("failure", e.getClass().getSimpleName());
            throw e;
        } finally {
            Gate gate = probe.gate.getAndSet(null);
            if (gate != null) gate.release.countDown();
            clients.shutdownNow();
            List<Throwable> cleanupFailures = new ArrayList<>();
            cleanup(() -> assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS)), cleanupFailures);
            cleanup(rebuilds::shutdown, cleanupFailures);
            if (server != null) {
                Tomcat closingServer = server;
                cleanup(closingServer::stop, cleanupFailures);
                cleanup(closingServer::destroy, cleanupFailures);
            }
            cleanup(factory::destroy, cleanupFailures);
            cleanup(() -> resources.shutdown().get(5, TimeUnit.SECONDS), cleanupFailures);
            root.setLevel(previous);
            report.put("freeMiBAtEnd", freeMiB());
            report.put("cleanupPassed", cleanupFailures.isEmpty());
            if (!cleanupFailures.isEmpty()) {
                report.put("completed", false);
                report.put("cleanupFailure", cleanupFailures.get(0).getClass().getSimpleName());
            }
            json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), report);
            if (!cleanupFailures.isEmpty()) throw new IllegalStateException("Fixture cleanup failed", cleanupFailures.get(0));
        }
    }

    private void phase(String name, int concurrency, int count, int id, int expected) throws Exception {
        requireMemory();
        probe.reset();
        CountDownLatch ready = new CountDownLatch(concurrency), start = new CountDownLatch(1);
        List<Future<List<Sample>>> tasks = new ArrayList<>();
        for (int worker = 0; worker < concurrency; worker++) {
            final int slot = worker;
            tasks.add(clients.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                List<Sample> results = new ArrayList<>();
                for (int i = slot; i < count; i += concurrency) {
                    if (i % 100 == 0) requireMemory();
                    results.add(request(name, i, id, expected, true));
                }
                return results;
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        long at = System.nanoTime();
        start.countDown();
        List<Sample> results = new ArrayList<>();
        for (Future<List<Sample>> task : tasks) results.addAll(task.get(30, TimeUnit.SECONDS));
        summarize(name, results, System.nanoTime() - at, concurrency);
    }

    private void uncheckedPhase(String name, int workers, int count, int id, int expected) {
        try { phase(name, workers, count, id, expected); }
        catch (Exception e) { throw new CompletionException(e); }
    }

    private Sample request(String phase, int sequence, int id, int expected, boolean record) throws Exception {
        long at = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(base.resolve(String.valueOf(id)))
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (record) synchronized (this) {
                samples.write(phase + "," + sequence + ",0," + (System.nanoTime() - at) + "," + expected + "\n");
            }
            throw e;
        }
        Sample sample = new Sample(response.statusCode(), System.nanoTime() - at);
        if (record) synchronized (this) {
            samples.write(phase + "," + sequence + "," + sample.status + "," + sample.ns + "," + expected + "\n");
        }
        assertEquals(expected, response.statusCode());
        JsonNode body = json.readTree(response.body());
        if (expected == 200) {
            assertEquals(id, body.path("id").asInt());
            assertEquals(2500, body.path("avgPrice").asInt());
            assertEquals("fixture type", body.path("typeName").asText());
            boolean staleExpected = phase.equals("stale-while-rebuild-blocked")
                    || phase.equals("controlled-rejection-stale");
            assertEquals(staleExpected ? "stale fixture" : "fixture " + id, body.path("name").asText());
        } else assertEquals(503, body.path("code").asInt());
        return sample;
    }

    private void summarize(String name, List<Sample> values, long elapsedNs, int concurrency) throws Exception {
        long[] sorted = values.stream().mapToLong(s -> s.ns).sorted().toArray();
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("phase", name);
        phase.put("concurrency", concurrency);
        phase.put("requests", values.size());
        phase.put("http200", values.stream().filter(s -> s.status == 200).count());
        phase.put("http503", values.stream().filter(s -> s.status == 503).count());
        phase.put("avgMs", Arrays.stream(sorted).average().orElseThrow() / 1e6);
        phase.put("p99Ms", sorted[(int) Math.ceil(sorted.length * .99) - 1] / 1e6);
        if (elapsedNs > 0) {
            phase.put("elapsedSeconds", elapsedNs / 1e9);
            phase.put("rps", values.size() * 1e9 / elapsedNs);
        }
        phase.put("shopSqlCompleted", probe.shopCompleted.get());
        phase.put("shopReadsStarted", probe.shops.get());
        phase.put("typeSql", probe.types.get());
        phase.put("freeMiB", freeMiB());
        phases.add(phase);
        samples.flush();
    }

    private Tomcat server(ShopController controller) throws Exception {
        Tomcat server = new Tomcat();
        Path base = Files.createTempDirectory("llmall-shop-http-");
        server.setBaseDir(base.toString());
        server.setHostname("127.0.0.1");
        server.setPort(0);
        server.getConnector().setProperty("address", "127.0.0.1");
        AbstractProtocol<?> protocol = (AbstractProtocol<?>) server.getConnector().getProtocolHandler();
        protocol.setMaxThreads(8);
        protocol.setMinSpareThreads(1);
        protocol.setMaxConnections(16);
        Context context = server.addContext("", base.toString());
        context.setParentClassLoader(getClass().getClassLoader());
        AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
        web.register(MvcConfig.class);
        web.addBeanFactoryPostProcessor(f -> {
            f.registerSingleton("shopController", controller);
            f.registerSingleton("commonExceptionAdvice", new CommonExceptionAdvice());
        });
        Tomcat.addServlet(context, "shop", new DispatcherServlet(web)).setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "shop");
        try {
            server.start();
        } catch (Exception e) {
            try { server.stop(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            try { server.destroy(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        return server;
    }

    private String createSchema() throws Exception {
        String schema = "llmall_shop_http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertTrue(schema.matches("llmall_shop_http_[0-9a-f]{12}"));
        Properties auth = new Properties();
        auth.setProperty("user", "root");
        auth.setProperty("password", Objects.requireNonNull(System.getenv("LLMALL_TEST_DB_PASSWORD")));
        try (Connection connection = DriverManager.getConnection(url(""), auth); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        return schema;
    }

    private String url(String schema) {
        return "jdbc:mysql://127.0.0.1:3307/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectTimeout=3000&socketTimeout=3000";
    }

    private void seed(JdbcTemplate db, String runId) {
        db.execute("CREATE TABLE fixture_owner(run_id VARCHAR(64) PRIMARY KEY)");
        db.update("INSERT INTO fixture_owner VALUES(?)", runId);
        db.execute("CREATE TABLE shop_type(id BIGINT PRIMARY KEY,name VARCHAR(64),icon VARCHAR(255),sort INT,create_time DATETIME,update_time DATETIME)");
        db.update("INSERT INTO shop_type(id,name,sort) VALUES(1,'fixture type',1)");
        db.execute("CREATE TABLE shop(id BIGINT PRIMARY KEY,name VARCHAR(64),type_id BIGINT,images VARCHAR(255),area VARCHAR(64),"
                + "address VARCHAR(255),x DOUBLE,y DOUBLE,avg_price BIGINT,sold INT,comments INT,score INT,hot_score DOUBLE,"
                + "hot_update_time DATETIME,last_review_time DATETIME,open_hours VARCHAR(64),status INT,create_time DATETIME,update_time DATETIME)");
        for (int batch = 0; batch < 10; batch++) {
            requireMemory();
            List<Object[]> rows = new ArrayList<>();
            for (int i = batch * 100 + 1; i <= (batch + 1) * 100; i++) rows.add(new Object[]{i, "fixture " + i});
            db.batchUpdate("INSERT INTO shop(id,name,type_id,images,area,address,x,y,avg_price,sold,comments,score,hot_score,open_hours,status) "
                    + "VALUES(?,?,1,'fixture.jpg','fixture area','fixture street',120.1,30.1,25,10,5,45,1.0,'09:00-22:00',1)", rows);
        }
    }

    private void awaitRebuild(ThreadPoolTaskExecutor rebuilds, StringRedisTemplate redis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((rebuilds.getActiveCount() > 0 || Boolean.TRUE.equals(redis.hasKey("lock:shop:1")))) {
            assertTrue(System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    private static long freeMiB() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getFreePhysicalMemorySize() / 1048576;
    }
    private static void requireMemory() { assertTrue(freeMiB() >= 1024, "Host free memory below 1GiB"); }
    private static class Sample {
        final int status; final long ns;
        Sample(int status, long ns) { this.status = status; this.ns = ns; }
    }
    @FunctionalInterface private interface CleanupAction { void run() throws Exception; }
    private static void cleanup(CleanupAction action, List<Throwable> failures) {
        try { action.run(); } catch (Throwable e) { failures.add(e); }
    }
    private static class Gate {
        final CountDownLatch entered;
        final CountDownLatch release = new CountDownLatch(1);
        Gate(int expected) { entered = new CountDownLatch(expected); }
    }
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class QueryProbe implements Interceptor {
        final AtomicInteger shops = new AtomicInteger(), types = new AtomicInteger(), shopCompleted = new AtomicInteger();
        final AtomicReference<Gate> gate = new AtomicReference<>();
        void reset() { shops.set(0); types.set(0); shopCompleted.set(0); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (statement.startsWith(ShopTypeMapper.class.getName() + ".")) types.incrementAndGet();
            if (statement.startsWith(ShopMapper.class.getName() + ".")) {
                shops.incrementAndGet();
                Gate current = gate.get();
                if (current != null) {
                    current.entered.countDown();
                    assertTrue(current.release.await(8, TimeUnit.SECONDS), "Controlled SQL gate timed out");
                }
            }
            Object result = invocation.proceed();
            if (statement.startsWith(ShopMapper.class.getName() + ".")) shopCompleted.incrementAndGet();
            return result;
        }
    }
}
