package com.llmall.content.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.utils.UserContext;
import com.llmall.content.domain.vo.FeedPageVO;
import com.llmall.content.mapper.*;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;

import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedCursorIntegrationTest {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 1, 1, 12, 0);
    private HikariDataSource pool;
    private JdbcTemplate db;
    private FollowServiceImpl service;
    private String schema;
    private Path output;
    private final List<String> passed = new ArrayList<>();
    private final List<Map<String, Object>> plans = new ArrayList<>();
    private SqlSessionFactory factory;

    @BeforeAll void start() throws Exception {
        output = Paths.get(Objects.requireNonNull(System.getenv("LLMALL_FEED_TEST_OUTPUT")));
        assertFalse(Files.exists(output));
        Files.createDirectories(output);
        schema = "llmall_feed_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertTrue(schema.matches("llmall_feed_test_[0-9a-f]{12}"));
        String password = Objects.requireNonNull(System.getenv("LLMALL_TEST_DB_PASSWORD"));
        Properties auth = new Properties();
        auth.setProperty("user", "root"); auth.setProperty("password", password);
        try (Connection c = DriverManager.getConnection(url(""), auth); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        pool = new HikariDataSource();
        pool.setJdbcUrl(url(schema)); pool.setUsername("root"); pool.setPassword(password);
        pool.setMaximumPoolSize(2); pool.setMinimumIdle(1); pool.setConnectionTimeout(3000);
        db = new JdbcTemplate(pool);
        db.execute("CREATE TABLE fixture_owner(id VARCHAR(64) PRIMARY KEY)");
        db.update("INSERT INTO fixture_owner VALUES(?)", schema);
        db.execute("CREATE TABLE blog(id BIGINT PRIMARY KEY,shop_id BIGINT,user_id BIGINT,title VARCHAR(64),images VARCHAR(255),"
                + "content VARCHAR(255),score INT,liked INT,comments INT,status INT,create_time DATETIME,update_time DATETIME,"
                + "KEY idx_user(user_id,create_time)) ENGINE=InnoDB");
        db.execute("CREATE TABLE `follow`(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,follow_user_id BIGINT,create_time DATETIME,"
                + "UNIQUE KEY uk_follow(user_id,follow_user_id),KEY idx_followed(follow_user_id)) ENGINE=InnoDB");
        db.execute("CREATE TABLE feed_inbox(id BIGINT PRIMARY KEY,user_id BIGINT,blog_id BIGINT,author_id BIGINT,create_time DATETIME,"
                + "UNIQUE KEY uk_user_blog(user_id,blog_id),KEY idx_user_id_desc(user_id,id DESC)) ENGINE=InnoDB");
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true); config.setCacheEnabled(false);
        config.addMapper(FeedInboxMapper.class); config.addMapper(BlogMapper.class); config.addMapper(FollowMapper.class);
        MybatisSqlSessionFactoryBean builder = new MybatisSqlSessionFactoryBean();
        builder.setDataSource(pool); builder.setConfiguration(config);
        factory = Objects.requireNonNull(builder.getObject());
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        service = new FollowServiceImpl(session.getMapper(FeedInboxMapper.class), session.getMapper(BlogMapper.class),
                mock(StringRedisTemplate.class), new BizMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(FollowMapper.class));
    }

    @BeforeEach void reset() {
        assertEquals(schema, db.queryForObject("SELECT id FROM fixture_owner", String.class));
        db.update("DELETE FROM feed_inbox"); db.update("DELETE FROM blog"); db.update("DELETE FROM `follow`");
        UserContext.setUser(1L);
    }
    @AfterEach void clearContext() { UserContext.removeUser(); }

    @Test void sameTimestampDoesNotDependOnInboxInsertionOrder() {
        for (int id = 1; id <= 5; id++) { blog(id, 2, TIME, 1); inbox(6-id, id, 2, TIME); }
        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), walk(2));
        passed.add("same-timestamp-inbox-order-no-loss");
    }
    @Test void pushedAndPulledCopyCannotReappearOnNextPage() {
        bigv(1000);
        blog(8, 1000, TIME, 1); blog(7, 1000, TIME.minusSeconds(1), 1);
        inbox(80, 8, 1000, TIME.plusSeconds(3)); // delivery time differs from publication
        assertEquals(List.of(8L, 7L), walk(1));
        passed.add("cross-source-dedup-across-pages");
    }
    @Test void missingAndHiddenHeadCannotHideOlderVisiblePosts() {
        blog(1, 2, TIME.minusSeconds(3), 1); blog(2, 2, TIME.minusSeconds(2), 1);
        blog(3, 2, TIME.minusSeconds(1), 2);
        for (int id = 1; id <= 4; id++) inbox(id, id, 2, TIME.plusSeconds(id));
        assertEquals(List.of(2L, 1L), ids(page(null, 2)));
        passed.add("hidden-and-deleted-head-filter-before-limit");
    }
    @Test void followingMoreThan200AuthorsDoesNotDropLastBigv() throws Exception {
        bigv(1000); // oldest follow row, outside the old first author window
        for (int n = 1; n <= 200; n++) db.update("INSERT INTO `follow`(user_id,follow_user_id) VALUES(1,?)", 1000+n);
        blog(50, 1000, TIME, 1);
        assertEquals(List.of(50L), ids(page(null, 10)));
        explain("201-authors", "com.llmall.content.mapper.BlogMapper.selectBigvFeedPage");
        explain("201-authors", "com.llmall.content.mapper.FeedInboxMapper.selectFeedPage");
        passed.add("201-authors-last-author-not-omitted");
    }
    @Test void prolificAuthorCannotMakeAFullPageLookExhausted() {
        bigv(1000);
        for (int id = 1; id <= 70; id++) blog(id, 1000, TIME.plusSeconds(id), 1);
        assertEquals(50, page(null, 50).getItems().size());
        assertEquals(70, walk(50).size());
        passed.add("prolific-author-not-truncated-in-memory");
    }
    @Test void newHeadDoesNotRepeatOrDisplaceRemainingPages() {
        bigv(1000);
        for (int id = 1; id <= 8; id++) {
            blog(id, id%2 == 0 ? 1000 : 2, TIME.plusSeconds(id/2), 1);
            if (id%2 != 0 || id == 8) inbox(100-id, id, id%2 == 0 ? 1000 : 2, TIME.plusSeconds(id/2+3));
        }
        FeedPageVO first = page(null, 3);
        List<Long> all = new ArrayList<>(ids(first));
        blog(100, 2, TIME.plusDays(1), 1); inbox(1000, 100, 2, TIME.plusDays(1));
        FeedPageVO current = first;
        for (int n = 0; n < 10; n++) {
            current = page(current, 3);
            if (current.getItems().isEmpty()) break;
            all.addAll(ids(current));
        }
        assertEquals(List.of(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L), all);
        assertEquals(100L, ids(page(null, 3)).get(0));
        passed.add("new-head-only-visible-after-refresh");
    }

    private FeedPageVO page(FeedPageVO previous, int size) {
        return service.queryFeedItemsV2(1L, previous == null ? null : previous.getNextLi(),
                previous == null ? null : previous.getNextCt(), previous == null ? null : previous.getNextCi(),
                previous == null ? null : previous.getNextAuthorId(), size);
    }
    private List<Long> walk(int size) {
        List<Long> all = new ArrayList<>(); Set<Long> unique = new HashSet<>(); FeedPageVO current = null;
        for (int n = 0; n < 100; n++) {
            current = page(current, size);
            for (Long id : ids(current)) { assertTrue(unique.add(id), "Repeated blog " + id); all.add(id); }
            if (!Boolean.TRUE.equals(current.getHasMore())) return all;
        }
        fail("Cursor did not terminate"); return all;
    }
    private List<Long> ids(FeedPageVO page) {
        return page.getItems().stream().map(item -> item.getBlogId()).collect(Collectors.toList());
    }
    private void blog(long id, long author, LocalDateTime time, int status) {
        db.update("INSERT INTO blog(id,user_id,title,status,create_time) VALUES(?,?,'fixture',?,?)", id, author, status, time);
    }
    private void inbox(long id, long blog, long author, LocalDateTime time) {
        db.update("INSERT INTO feed_inbox VALUES(?,1,?,?,?)", id, blog, author, time);
    }
    private void bigv(long author) {
        db.update("INSERT INTO `follow`(user_id,follow_user_id) VALUES(1,?)", author);
        List<Object[]> fans = new ArrayList<>();
        for (int n = 0; n < 1000; n++) fans.add(new Object[]{10000+n, author});
        new TransactionTemplate(new DataSourceTransactionManager(pool)).executeWithoutResult(status ->
                db.batchUpdate("INSERT INTO `follow`(user_id,follow_user_id) VALUES(?,?)", fans));
    }
    private void explain(String fixture, String statementId) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", 1L); params.put("threshold", 1000); params.put("ct", null); params.put("ci", null); params.put("limit", 51);
        MappedStatement statement = factory.getConfiguration().getMappedStatement(statementId);
        BoundSql bound = statement.getBoundSql(params);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = pool.getConnection(); PreparedStatement query = c.prepareStatement("EXPLAIN " + bound.getSql())) {
            new DefaultParameterHandler(statement, params, bound).setParameters(query);
            try (ResultSet results = query.executeQuery()) {
                ResultSetMetaData columns = results.getMetaData();
                while (results.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int col = 1; col <= columns.getColumnCount(); col++) row.put(columns.getColumnLabel(col), results.getObject(col));
                    rows.add(row);
                }
            }
        }
        plans.add(Map.of("fixture", fixture, "statement", statementId, "rows", rows));
    }
    @AfterAll void close() throws Exception {
        if (pool != null) pool.close();
        if (output != null) new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),
                Map.of("schema", schema, "passed", passed, "completed", passed.size() == 6, "explain", plans));
    }
    private static String url(String schema) {
        return "jdbc:mysql://127.0.0.1:3307/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000";
    }
}
