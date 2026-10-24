package com.llmall.user;

import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.user.domain.dto.SmsLoginDTO;
import com.llmall.user.domain.vo.TokenPair;
import com.llmall.user.service.ITokenService;
import com.llmall.user.service.IUserService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证域的并发正确性验证（不是压测）：它验证的不变量是"同一个 refresh token 并发刷新恰好成功 1 次"
 * 与"同一个短信验证码并发登录恰好成功 1 次"。
 *
 * 【注意】这两条规则在单线程下必然成立（第二次调用时旧值已被第一次删掉），单纯压测也只会报
 * "无报错、QPS 正常"，只有两个请求几乎同时读到同一份值才会被击穿。
 *
 * 【注意】必须连真实 Redis（原子性、TTL、竞态都是 Redis 的真实行为，把
 * {@code StringRedisTemplate} mock 掉等于什么都没测）；标注 integration 后默认不参与 mvn test，
 * 需要时用 -Dgroups=integration -DexcludedGroups= 显式开启。
 */
@Tag("integration")
@SpringBootTest
class AuthConcurrencyTest {

    /** 并发线程数：只需"同时到达"即可暴露这类竞态，不必到压测量级 */
    private static final int CONCURRENCY = 8;

    @Autowired
    private ITokenService tokenService;

    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 同一个 refresh token 被 {@value #CONCURRENCY} 个线程同时拿去刷新：必须恰好 1 个成功。
     *
     * 旧 refresh 用掉即失效是轮换（OAuth2 推荐实践）的核心承诺：一旦被并发击穿，攻击者与真实用户
     * 会双双拿到有效令牌，而且没有任何异常信号，"互相把对方刷失效"这个泄露检测手段随之失效。
     */
    @Test
    void concurrentRefreshOfSameTokenSucceedsExactlyOnce() throws Exception {
        TokenPair pair = tokenService.issue(90001L);

        // 用栅栏把线程压到同一瞬间再放行，最大化"同时读到值"的概率
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        AtomicReference<TokenPair> winner = new AtomicReference<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    TokenPair fresh = tokenService.refresh(pair.getRefreshToken());
                    success.incrementAndGet();
                    winner.set(fresh);
                } catch (UnauthorizedException e) {
                    rejected.incrementAndGet();
                } catch (Throwable t) {
                    // 其它异常不算"并发正确"，单独收集，不能混进"被拒绝"里
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                } finally {
                    done.countDown();
                }
            }, "auth-refresh-" + i).start();
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).as("线程未能在 10s 内就绪").isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("刷新未能在 30s 内全部结束").isTrue();

        assertThat(unexpected).as("出现非预期异常（既不是成功也不是 UnauthorizedException）").isEmpty();
        assertThat(success.get())
                .as("同一个 refresh token 并发刷新：成功次数必须恰好为 1（改前会出现多个成功）")
                .isEqualTo(1);
        assertThat(rejected.get())
                .as("其余 %s 个并发请求都应被判为令牌失效", CONCURRENCY - 1)
                .isEqualTo(CONCURRENCY - 1);

        // 轮换后：新 refresh 已写入白名单，旧 refresh 必须已从白名单移除
        String newJti = extractJti(winner.get().getRefreshToken());
        assertThat(redisTemplate.opsForValue().get(AuthConstants.REDIS_REFRESH + newJti))
                .as("轮换后签发的新 refresh 必须在白名单里")
                .isEqualTo("90001");
        assertThat(redisTemplate.opsForValue().get(AuthConstants.REDIS_REFRESH + extractJti(pair.getRefreshToken())))
                .as("被轮换掉的旧 refresh 必须已从白名单移除")
                .isNull();

        cleanupRefresh(pair, winner.get());
    }

    /** 顺序调用两次也只能成功一次：作为"一次一换"语义的单线程基线 */
    @Test
    void refreshTokenIsSingleUseInSequence() {
        TokenPair pair = tokenService.issue(90001L);

        TokenPair rotated = tokenService.refresh(pair.getRefreshToken());
        assertThat(rotated.getRefreshToken()).isNotEqualTo(pair.getRefreshToken());

        UnauthorizedException second = org.junit.jupiter.api.Assertions.assertThrows(
                UnauthorizedException.class,
                () -> tokenService.refresh(pair.getRefreshToken()),
                "同一个 refresh 用第二次必须失败");
        assertThat(second.getMessage()).contains("失效");

        cleanupRefresh(pair, rotated);
    }

    /**
     * 同一个短信验证码被 {@value #CONCURRENCY} 个线程同时拿去登录：必须恰好 1 个成功。
     *
     * 验证码是一次性登录凭证：并发下若被多次核销，同一个验证码就能换出多个登录态。
     */
    @Test
    void concurrentSmsLoginWithSameCodeSucceedsExactlyOnce() throws Exception {
        String phone = uniquePhone();
        userService.sendCode(phone);
        String code = redisTemplate.opsForValue().get(AuthConstants.REDIS_LOGIN_CODE + phone);
        assertThat(code).as("验证码应已写入 Redis").isNotNull();

        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    userService.loginByCode(smsLogin(phone, code));
                    success.incrementAndGet();
                } catch (com.llmall.common.exception.BizIllegalException e) {
                    rejected.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                } finally {
                    done.countDown();
                }
            }, "auth-sms-" + i).start();
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).as("线程未能在 10s 内就绪").isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("登录未能在 30s 内全部结束").isTrue();

        assertThat(unexpected).as("出现非预期异常").isEmpty();
        assertThat(success.get())
                .as("同一个验证码并发登录：成功次数必须恰好为 1（改前会出现多个成功）")
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(CONCURRENCY - 1);
        assertThat(redisTemplate.hasKey(AuthConstants.REDIS_LOGIN_CODE + phone))
                .as("验证码核销后必须从 Redis 移除")
                .isFalse();
    }

    /** 填错码与已核销/已过期必须给出不同提示：前者允许重试，后者要求重新获取 */
    @Test
    void wrongAndExpiredCodeGiveDistinctMessages() {
        String phone = uniquePhone();
        userService.sendCode(phone);

        com.llmall.common.exception.BizIllegalException wrong = org.junit.jupiter.api.Assertions.assertThrows(
                com.llmall.common.exception.BizIllegalException.class,
                () -> userService.loginByCode(smsLogin(phone, "000000")),
                "填错验证码应报错");
        assertThat(wrong.getMessage()).as("填错码时用户还能再试一次，提示应是'验证码错误'").contains("验证码错误");

        String code = redisTemplate.opsForValue().get(AuthConstants.REDIS_LOGIN_CODE + phone);
        userService.loginByCode(smsLogin(phone, code));

        com.llmall.common.exception.BizIllegalException gone = org.junit.jupiter.api.Assertions.assertThrows(
                com.llmall.common.exception.BizIllegalException.class,
                () -> userService.loginByCode(smsLogin(phone, code)),
                "已核销的验证码再用应报错");
        assertThat(gone.getMessage()).as("已过期/已核销时应提示重新获取，而不是让用户继续试").contains("过期");
    }

    /** 从 JWT 里取 jti（只做 base64 解码，不验签 —— 测试断言用） */
    private String extractJti(String jwt) {
        String[] parts = jwt.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        int i = payload.indexOf("\"jti\"");
        int start = payload.indexOf('"', payload.indexOf(':', i) + 1) + 1;
        int end = payload.indexOf('"', start);
        return payload.substring(start, end);
    }

    /** 清掉测试签发的 refresh 白名单条目，避免污染现役 Redis */
    private void cleanupRefresh(TokenPair... pairs) {
        for (TokenPair p : pairs) {
            if (p == null || p.getRefreshToken() == null) {
                continue;
            }
            try {
                redisTemplate.delete(AuthConstants.REDIS_REFRESH + extractJti(p.getRefreshToken()));
            } catch (Exception ignore) {
                // 清理失败不影响断言结论
            }
        }
        redisTemplate.delete(AuthConstants.REDIS_REFRESH_USER + 90001L);
    }

    /** 每次跑用一个全新的手机号，避免与演示库里的既有数据互相影响 */
    private String uniquePhone() {
        long suffix = System.currentTimeMillis() % 100000000L;
        return "139" + String.format("%08d", suffix);
    }

    /** SmsLoginDTO 没有开 @Accessors(chain=true)，setter 返回 void，不能链式构造 */
    private SmsLoginDTO smsLogin(String phone, String code) {
        SmsLoginDTO dto = new SmsLoginDTO();
        dto.setPhone(phone);
        dto.setCode(code);
        return dto;
    }

    public static void main(String[] args) throws Exception {
        // 【注意】本测试只需要 Redis 与 JWT，让 bootstrap 去拉 Nacos 会引入两个与目标无关的失败源：
        // Windows 下 `java -cp ...` 的默认 file.encoding 是 GBK，Nacos 客户端按平台编码解码 UTF-8
        // 配置会抛 MalformedInputException；配置中心不可用时测试直接起不来。故这里强制关闭 Nacos。
        System.setProperty("spring.cloud.nacos.config.enabled", "false");
        System.setProperty("spring.cloud.nacos.discovery.enabled", "false");
        // 摘掉 Nacos 之后 shared-jdbc.yaml 提供的 spring.datasource.url 也没了，而 MyBatis 的 Mapper
        // 在容器启动时就会初始化（@MapperScan），没有 SqlSessionFactory 会直接失败。所以必须补齐：
        // 给一条"只连库、不写业务表"的连接即可（本测试只读写 Redis 与 auth:* key）。
        System.setProperty("spring.datasource.url",
                "jdbc:mysql://192.168.189.128:3306/llmall-user"
                        + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        System.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        System.setProperty("spring.datasource.username", "root");
        System.setProperty("spring.datasource.password", "123");
        // 同理 shared-redis.yaml 也来自 Nacos：不补这些参数，Redis 会退化成默认的 localhost:6379
        // 并以 RedisConnectionFailureException 让全部断言失败（那是在测配置，不是在测原子性）。
        // 这里刻意用与 Nacos 共享配置同样的口径（含哨兵 + 超时），顺带覆盖哨兵模式的真实连接。
        System.setProperty("spring.redis.password", "123456");
        System.setProperty("spring.redis.connect-timeout", "2s");
        System.setProperty("spring.redis.timeout", "2s");
        System.setProperty("spring.redis.sentinel.master", "llmall-master");
        System.setProperty("spring.redis.sentinel.nodes",
                "192.168.189.128:26379,192.168.189.128:26380,192.168.189.128:26381");

        ConfigurableApplicationContext ctx = SpringApplication.run(
                UserApplication.class, new String[]{"--server.port=0"});
        int failed = 0;
        try {
            AuthConcurrencyTest test = new AuthConcurrencyTest();
            ctx.getAutowireCapableBeanFactory().autowireBean(test);
            failed += run("【并发】同一 refresh 并发刷新，恰好 1 次成功", () -> {
                try {
                    test.concurrentRefreshOfSameTokenSucceedsExactlyOnce();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            failed += run("【顺序】refresh 一次一换", test::refreshTokenIsSingleUseInSequence);
            failed += run("【并发】同一验证码并发登录，恰好 1 次成功", () -> {
                try {
                    test.concurrentSmsLoginWithSameCodeSucceedsExactlyOnce();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            failed += run("【语义】填错码 / 已核销的提示语不同", test::wrongAndExpiredCodeGiveDistinctMessages);
        } finally {
            ctx.close();
        }
        System.out.println(failed == 0
                ? "== 认证域并发正确性检查全部通过 =="
                : "== 认证域有 " + failed + " 项失败 ==");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static int run(String name, Runnable body) {
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
