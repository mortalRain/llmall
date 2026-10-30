package com.llmall.search.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.search.domain.po.SearchHistory;
import com.llmall.search.mapper.SearchHistoryMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real-MySQL correctness + DAO microbenchmark. Never uses a business schema. */
@Tag("integration")
class SearchHistoryMysqlTest {
    private static final int REQUESTS = 2000;
    private static final int WORKERS = 4;
    private static final String WORD = "benchmark-key";

    @Test void isolatedMysqlCorrectnessAndDuplicateWriteBenchmark() throws Exception {
        String password = System.getenv("LLMALL_TEST_DB_PASSWORD");
        assertNotNull(password, "Set LLMALL_TEST_DB_PASSWORD; it is never logged");
        String schema = "llmall_hist_bench_" + UUID.randomUUID().toString().replace("-", "").substring(0,12);
        assertTrue(schema.matches("llmall_hist_bench_[0-9a-f]{12}"));
        String base = "jdbc:mysql://127.0.0.1:3307/";
        Properties credentials = new Properties(); credentials.setProperty("user", "root"); credentials.setProperty("password", password);
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=5000";
        requireMemory();
        try (Connection c = DriverManager.getConnection(base + options, credentials); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        Path output = Paths.get(Objects.requireNonNull(System.getenv("LLMALL_BENCH_OUTPUT"), "Set LLMALL_BENCH_OUTPUT"));
        Files.createDirectories(output);
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schema", schema); report.put("scope", "DAO microbenchmark, not HTTP search latency");
        report.put("fixtureRows", 10000); report.put("workers", WORKERS); report.put("requestsPerPhase", REQUESTS);
        report.put("schemaRetained", true);
        Files.writeString(output.resolve("fixture-schema.txt"), schema);
        Logger sqlLogger = (Logger) LoggerFactory.getLogger(SearchHistoryMapper.class);
        Level previous = sqlLogger.getLevel(); sqlLogger.setLevel(Level.ERROR);
        ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        List<Map<String,Object>> phases = new ArrayList<>();
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl(base + schema + options); pool.setUsername("root"); pool.setPassword(password);
            pool.setMaximumPoolSize(WORKERS); pool.setMinimumIdle(1); pool.setConnectionTimeout(5000);
            pool.setConnectionInitSql("SET SESSION sql_mode='STRICT_ALL_TABLES'");
            JdbcTemplate db = new JdbcTemplate(pool);
            db.execute("CREATE TABLE fixture_owner(run_id VARCHAR(64) PRIMARY KEY, purpose VARCHAR(64))");
            db.update("INSERT INTO fixture_owner VALUES(?,?)", schema, "search-history-benchmark-only");
            db.execute("CREATE TABLE search_history(id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,"
                    + "keyword VARCHAR(64) NOT NULL,create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "KEY idx_user_time(user_id,create_time DESC),UNIQUE KEY uk_user_word(user_id,keyword)) ENGINE=InnoDB");
            try (Connection c=pool.getConnection(); PreparedStatement s=c.prepareStatement("INSERT INTO search_history(user_id,keyword) VALUES(?,?)")) {
                c.setAutoCommit(false);
                for (int i=0;i<10000;i++) {
                    s.setLong(1,i==0?1:100+i%100);s.setString(2,i==0?WORD:"fixture-"+i);s.addBatch();
                    if ((i+1)%250==0) s.executeBatch();
                }
                c.commit();
            }
            report.put("mysqlVersion",db.queryForObject("SELECT VERSION()",String.class));
            MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(pool);
            MybatisConfiguration cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(SearchHistoryMapper.class);
            factory.setConfiguration(cfg);
            SearchHistoryMapper mapper=new SqlSessionTemplate(factory.getObject()).getMapper(SearchHistoryMapper.class);
            Map<String,Object> original=db.queryForMap("SELECT id,create_time FROM search_history WHERE user_id=1 AND keyword=?",WORD);

            // Concurrent duplicate writes preserve row facts; users and Unicode boundaries remain independent.
            runWrites(workers,100,()->mapper.insertIfAbsent(1L,WORD));
            assertEquals(original,db.queryForMap("SELECT id,create_time FROM search_history WHERE user_id=1 AND keyword=?",WORD));
            mapper.insertIfAbsent(2L,WORD);assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM search_history WHERE keyword=?",Integer.class,WORD));
            String unicode="\uD83C\uDFEC".repeat(64);mapper.insertIfAbsent(3L,unicode);
            assertEquals(unicode,db.queryForObject("SELECT keyword FROM search_history WHERE user_id=3",String.class));
            assertThrows(DataAccessException.class,()->mapper.insertIfAbsent(4L,null));
            assertThrows(DataAccessException.class,()->mapper.insertIfAbsent(4L,"店".repeat(65)));
            report.put("correctness", "concurrency, id/time preservation, user isolation, Unicode and strict SQL errors passed");

            try (BufferedWriter raw=Files.newBufferedWriter(output.resolve("requests.csv"))) {
                raw.write("round,implementation,sequence,latencyNs,duplicateException\n");
                for (String mode:List.of("legacy","idempotent")) runWrites(workers,1000,()->write(mapper,mode));
                for (int round=1;round<=3;round++) {
                    requireMemory();
                    List<String> order=round%2==1?List.of("legacy","idempotent"):List.of("idempotent","legacy");
                    for(String mode:order) {
                        requireMemory();
                        double[] nanos=new double[REQUESTS];boolean[] duplicates=new boolean[REQUESTS];
                        AtomicInteger next=new AtomicInteger();CountDownLatch ready=new CountDownLatch(WORKERS),start=new CountDownLatch(1);
                        List<Future<?>> tasks=new ArrayList<>();
                        for(int t=0;t<WORKERS;t++) tasks.add(workers.submit(()->{
                            ready.countDown();start.await();int i;
                            while((i=next.getAndIncrement())<REQUESTS) {
                                long at=System.nanoTime();duplicates[i]=write(mapper,mode);nanos[i]=System.nanoTime()-at;
                            }return null;
                        }));
                        assertTrue(ready.await(10,TimeUnit.SECONDS));long at=System.nanoTime();start.countDown();
                        for(Future<?> task:tasks) task.get(60,TimeUnit.SECONDS);
                        double seconds=(System.nanoTime()-at)/1e9;int duplicateCount=0;
                        for(int i=0;i<REQUESTS;i++) {if(duplicates[i])duplicateCount++;raw.write(round+","+mode+","+(i+1)+","+(long)nanos[i]+","+duplicates[i]+"\n");}
                        raw.flush();Arrays.sort(nanos);
                        Map<String,Object> phase=new LinkedHashMap<>();phase.put("round",round);phase.put("implementation",mode);
                        phase.put("requests",REQUESTS);phase.put("elapsedSeconds",seconds);phase.put("rps",REQUESTS/seconds);
                        phase.put("avgMs",Arrays.stream(nanos).average().orElseThrow()/1e6);
                        phase.put("p95Ms",nanos[(int)Math.ceil(REQUESTS*.95)-1]/1e6);phase.put("p99Ms",nanos[(int)Math.ceil(REQUESTS*.99)-1]/1e6);
                        phase.put("duplicateExceptions",duplicateCount);phase.put("freeMiB",freeMiB());phases.add(phase);
                        assertEquals(mode.equals("legacy")?REQUESTS:0,duplicateCount);
                    }
                }
            }
            assertEquals(original,db.queryForMap("SELECT id,create_time FROM search_history WHERE user_id=1 AND keyword=?",WORD));
            report.put("finalRows",db.queryForObject("SELECT COUNT(*) FROM search_history",Integer.class));
            report.put("completed",true);
        } finally {
            workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));sqlLogger.setLevel(previous);
            report.put("phases",phases);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),report);
        }
    }
    private static boolean write(SearchHistoryMapper mapper,String mode) {
        if(mode.equals("idempotent")) {mapper.insertIfAbsent(1L,WORD);return false;}
        try {mapper.insert(new SearchHistory().setUserId(1L).setKeyword(WORD));return false;}
        catch(DuplicateKeyException expected){return true;}
    }
    private static void runWrites(ExecutorService workers,int count,Runnable write) throws Exception {
        List<Future<?>> futures=new ArrayList<>();for(int i=0;i<count;i++)futures.add(workers.submit(write));
        for(Future<?> f:futures)f.get(30,TimeUnit.SECONDS);
    }
    private static long freeMiB() {
        return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getFreePhysicalMemorySize()/1048576;
    }
    private static void requireMemory(){assertTrue(freeMiB()>=1024,"Host free memory below 1024MiB; stop benchmark");}
}
