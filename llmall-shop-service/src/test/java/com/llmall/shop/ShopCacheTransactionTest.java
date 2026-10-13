package com.llmall.shop;

import com.llmall.api.dto.ShopDTO;
import com.llmall.shop.domain.po.Shop;
import com.llmall.shop.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门店详情缓存与重建锁的回归测试（B6）。
 *
 * 需要真实依赖：中间件在 192.168.189.128（MySQL/Redis/Nacos），配置来自 Nacos 共享配置，
 * 所以这是一个 @SpringBootTest —— 这几条断言恰恰是"必须连真 Redis 才有意义"的，
 * 用 Mock 掉 RedisTemplate 的写法测不出任何东西（TTL、事务提交时机、锁的归属都是 Redis/事务的真实行为）。
 *
 * 覆盖：
 *  1. 冷 key 回填后 cache:shop:{id} 的 TTL 必须 > 0（原来写入的 key 永不过期）；
 *  2. 查不到的门店写的空值哨兵也必须带 TTL（防匿名刷随机 id 把 Redis 内存刷爆）；
 *  3. 事务**提交后**缓存才被失效；
 *  4. 事务**回滚**时缓存不能被误删（原来在事务里直接删，回滚也照删）。
 */
@SpringBootTest
class ShopCacheTransactionTest {

    /** 库里真实存在的门店 id */
    private static final long SHOP_ID = 112L;

    /** 库里不存在的门店 id，用来验证"空值哨兵" */
    private static final long GHOST_ID = 987654321L;

    private static final String SHOP_CACHE_KEY = "cache:shop:";

    @Autowired
    private IShopService shopService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 冷 key（真实门店）：回填后必须有物理 TTL，且逻辑过期时间必须早于物理 TTL（逻辑过期方案的前提） */
    @Test
    void coldKeyRebuildWritesKeyWithTtl() {
        String key = SHOP_CACHE_KEY + SHOP_ID;
        redisTemplate.delete(key);

        ShopDTO dto = shopService.queryById(SHOP_ID);
        assertThat(dto).as("门店 %s 应存在", SHOP_ID).isNotNull();

        Long ttlSeconds = redisTemplate.getExpire(key);
        assertThat(redisTemplate.hasKey(key)).as("回填后缓存 key 应存在").isTrue();
        assertThat(ttlSeconds).as("cache:shop:%s 的 TTL 必须 > 0，实际=%s", SHOP_ID, ttlSeconds)
                .isNotNull().isGreaterThan(0L);
        // 物理 TTL 应大于逻辑过期时长（默认 30 分钟），否则"逻辑过期后仍返回旧值"无从谈起
        assertThat(ttlSeconds).as("物理 TTL 应大于逻辑过期时长 30 分钟").isGreaterThan(30L * 60L);
    }

    /** 不存在的门店：也要写哨兵（防穿透），且哨兵必须带 TTL、TTL 要短 */
    @Test
    void nullSentinelKeyIsWrittenWithPositiveShortTtl() {
        String key = SHOP_CACHE_KEY + GHOST_ID;
        redisTemplate.delete(key);

        assertThat(shopService.queryById(GHOST_ID)).as("不存在的门店应返回 null").isNull();

        Long ttlSeconds = redisTemplate.getExpire(key);
        assertThat(redisTemplate.hasKey(key)).as("不存在的门店也要写空值哨兵").isTrue();
        assertThat(redisTemplate.opsForValue().get(key))
                .as("哨兵的 data 应为 null 且带 expireTime")
                .contains("\"data\":null").contains("expireTime");
        assertThat(ttlSeconds).as("空值哨兵的 TTL 必须 > 0，实际=%s", ttlSeconds)
                .isNotNull().isGreaterThan(0L);
        assertThat(ttlSeconds).as("空值哨兵 TTL 应该很短（默认 2 分钟 + 抖动）").isLessThanOrEqualTo(4L * 60L);

        redisTemplate.delete(key);
    }

    /** 提交路径：事务提交之后，缓存必须被失效 */
    @Test
    void cacheEvictedAfterCommit() {
        String key = SHOP_CACHE_KEY + SHOP_ID;
        redisTemplate.delete(key);
        shopService.queryById(SHOP_ID);
        assertThat(redisTemplate.hasKey(key)).as("前置条件：缓存已预热").isTrue();

        transactionTemplate.execute(status -> shopService.recomputeHotScore(SHOP_ID));

        assertThat(redisTemplate.hasKey(key))
                .as("事务提交后缓存应被失效（afterCommit）").isFalse();
    }

    /**
     * 回滚路径：事务回滚时，缓存**不能**被删。
     *
     * 这就是原实现的缺陷：它在本方法返回前直接 redisTemplate.delete，
     * 删除发生在本事务提交之前 —— 并发读请求此刻回源只会读到**未提交的旧值**，
     * 再把这个旧值写回缓存（TTL 最长 30 分钟），于是详情页与列表页长期不一致。
     * 改成 afterCommit 后：提交成功才删；回滚什么都不做（DB 与缓存都还是旧值，本来就是一致的）。
     */
    @Test
    void cacheKeptWhenTransactionRollsBack() {
        String key = SHOP_CACHE_KEY + SHOP_ID;
        redisTemplate.delete(key);
        shopService.queryById(SHOP_ID);
        assertThat(redisTemplate.hasKey(key)).as("前置条件：缓存已预热").isTrue();

        Shop before = shopService.getById(SHOP_ID);
        final boolean[] cacheAliveInsideTx = new boolean[1];

        transactionTemplate.execute(status -> {
            shopService.recomputeHotScore(SHOP_ID);
            // 事务内部（尚未提交）就不该提前失效
            cacheAliveInsideTx[0] = Boolean.TRUE.equals(redisTemplate.hasKey(key));
            status.setRollbackOnly();
            return null;
        });

        // 事务确实回滚了：DB 里的热度分没变（否则下面的断言就没有意义）
        Shop after = shopService.getById(SHOP_ID);
        assertThat(after.getHotScore()).as("事务已回滚，DB 热度分不应变化").isEqualTo(before.getHotScore());
        assertThat(cacheAliveInsideTx[0]).as("事务提交之前不允许提前失效缓存").isTrue();
        assertThat(redisTemplate.hasKey(key)).as("事务回滚后缓存不能被误删").isTrue();
    }

    // ==================== 离线可跑的入口 ====================

    /**
     * 不依赖 JUnit 的入口：本仓库的 Maven 离线仓库里**没有** surefire 的 junit-platform provider
     * （mvn test 会直接报 "Missing: org.apache.maven.surefire:surefire-junit-platform:jar:2.22.2"），
     * 所以这里用 Spring Boot 自己的 PropertiesLauncher 跑同一批断言：
     *
     * <pre>
     * java -Dloader.path=&lt;test-classes 目录&gt;;&lt;assertj-core.jar&gt; \
     *      -Dloader.main=com.llmall.shop.ShopCacheTransactionTest \
     *      -cp llmall-shop-service.jar org.springframework.boot.loader.PropertiesLauncher
     * </pre>
     *
     * 任一条断言失败会打印 FAIL 并以非 0 退出码结束，便于在验证脚本里判断。
     */
    public static void main(String[] args) throws Exception {
        String[] appArgs = new String[]{"--server.port=0"};
        ConfigurableApplicationContext ctx = SpringApplication.run(ShopApplication.class, appArgs);
        int failed = 0;
        try {
            ShopCacheTransactionTest test = new ShopCacheTransactionTest();
            ctx.getAutowireCapableBeanFactory().autowireBean(test);
            failed += run(test, "冷 key 回填后带物理 TTL（且物理 TTL > 逻辑过期时长）", test::coldKeyRebuildWritesKeyWithTtl);
            failed += run(test, "空值哨兵也带 TTL 且 TTL 很短", test::nullSentinelKeyIsWrittenWithPositiveShortTtl);
            failed += run(test, "提交后缓存被失效", test::cacheEvictedAfterCommit);
            failed += run(test, "回滚时缓存不被误删", test::cacheKeptWhenTransactionRollsBack);
        } finally {
            ctx.close();
        }
        System.out.println(failed == 0 ? "== B6 检查全部通过 ==" : "== B6 有 " + failed + " 项失败 ==");
        if (failed > 0) {
            System.exit(1);
        }
        System.exit(0);
    }

    private static int run(ShopCacheTransactionTest test, String name, Runnable body) {
        try {
            body.run();
            System.out.println("[PASS] " + name);
            return 0;
        } catch (Throwable t) {
            System.out.println("[FAIL] " + name + " -> " + t);
            return 1;
        }
    }
}
