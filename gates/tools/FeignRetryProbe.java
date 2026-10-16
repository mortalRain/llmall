import com.llmall.api.config.NoRetryFeignConfig;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F-07 的测试化验证（2026-10-15）：证明「超时场景下，请求只发出一次」。
 *
 * 【为什么要有它】台账 F-07 原本写的是"需要运行环境做单次调用验证"。其实不需要起整个服务：
 * 用 JDK 自带的 com.sun.net.httpserver 起一个"收到请求就挂住直到超时"的桩服务端，
 * 让真实 Feign 客户端去打它，数服务端收到了几次请求即可。
 *
 * 【关键设计：对照实验】只断言"禁重试时只有 1 次"是没有说服力的 —— 万一那条路径本来就不重试呢？
 * 所以同时跑两组：
 *   A 组：Retryer = 生产代码的 NoRetryFeignConfig.feignRetryer()（即 NEVER_RETRY）→ 期望服务端恰好收到 1 次
 *   B 组：Retryer = new Retryer.Default(100, 200, 3)（Feign 自带的重试器）      → 期望服务端收到多于 1 次
 * B 组证明"这条路径确实会重试"，A 组的 1 次才有意义。
 *
 * 【不引入新依赖】用 JDK 自带的 HttpServer，不引 WireMock；Retryer 直接取生产代码里的那一个，
 * 而不是在测试里重新写一遍 "NEVER_RETRY"（否则验证的是测试自己的复述，不是产品代码）。
 *
 * 用法：java -cp <feign-core + llmall-api/classes + ...> FeignRetryProbe.java
 * 退出码：0 = A 组恰好 1 次且 B 组 > 1 次；1 = 不符合预期。
 */
public class FeignRetryProbe {

    interface SlowApi {
        @RequestLine("POST /slow")
        String slow();
    }

    static final AtomicInteger hits = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            hits.incrementAndGet();
            try {
                // 挂住，让客户端必然读到超时
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) {
                // 客户端早已超时断开，这里失败是预期内的
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        System.out.println("桩服务端已启动: " + base + "/slow （handler 收到请求后挂起 1500ms）");

        // 读超时 800ms < 挂起 1500ms → 必然触发 Feign 的超时分支
        Request.Options options = new Request.Options(1000, TimeUnit.MILLISECONDS, 800, TimeUnit.MILLISECONDS, true);

        // ---- A 组：生产代码的不重试策略 ----
        Retryer never = new NoRetryFeignConfig().feignRetryer();   // 直接取产品代码里的那一个
        SlowApi apiA = Feign.builder().retryer(never).options(options).target(SlowApi.class, base);
        hits.set(0);
        long t0 = System.currentTimeMillis();
        try {
            apiA.slow();
            System.out.println("A 组：调用居然成功了（不该发生）");
        } catch (Exception e) {
            System.out.println("A 组：按预期抛出 " + e.getClass().getSimpleName());
        }
        int hitsA = hits.get();
        long costA = System.currentTimeMillis() - t0;
        System.out.println("A 组（NoRetryFeignConfig.feignRetryer()）：服务端收到 " + hitsA + " 次，耗时 " + costA + "ms");

        // ---- B 组：Feign 自带重试器（对照组，证明这条路径确实会重试）----
        SlowApi apiB = Feign.builder().retryer(new Retryer.Default(100, 200, 3)).options(options).target(SlowApi.class, base);
        hits.set(0);
        long t1 = System.currentTimeMillis();
        try {
            apiB.slow();
            System.out.println("B 组：调用居然成功了（不该发生）");
        } catch (Exception e) {
            System.out.println("B 组：按预期抛出 " + e.getClass().getSimpleName());
        }
        int hitsB = hits.get();
        long costB = System.currentTimeMillis() - t1;
        System.out.println("B 组（Retryer.Default(100,200,3)）：服务端收到 " + hitsB + " 次，耗时 " + costB + "ms");

        server.stop(0);

        System.out.println();
        boolean okA = hitsA == 1;
        boolean okB = hitsB > 1;
        System.out.println("断言 A（禁重试 → 恰好 1 次）: " + (okA ? "通过 ✔" : "失败 ✘（实际 " + hitsA + " 次）"));
        System.out.println("断言 B（有重试 → 多于 1 次，证明对照有效）: " + (okB ? "通过 ✔" : "失败 ✘（实际 " + hitsB + " 次）"));
        System.out.println();
        if (okA && okB) {
            System.out.println("结论: 通过 —— 超时场景下，使用 NoRetryFeignConfig 的请求只发出 1 次");
            System.exit(0);
        }
        System.out.println("结论: 失败");
        System.exit(1);
    }
}
