package com.llmall.cart;
import com.llmall.api.config.DefaultFeignConfig;
import com.llmall.api.config.NoRetryFeignConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;

/**
 * 购物车服务。
 *
 * 【注意】defaultConfiguration 必须显式声明，且两个配置类都要带上：
 * - {@link DefaultFeignConfig}：透传 X-Trace-Id 与内部凭证（x-internal-token / x-service-token / user-info）。
 *   本服务此前漏配，后果是 cart→item 的调用不带 traceId（跨服务日志断链），
 *   且一旦将来调用受保护的写接口会直接 403。
 * - {@link NoRetryFeignConfig}：显式禁止 Feign 自动重试（写接口重试会重复扣减）。
 */
@EnableFeignClients(basePackages = "com.llmall.api.client",
        defaultConfiguration = {DefaultFeignConfig.class, NoRetryFeignConfig.class})
@MapperScan("com.llmall.cart.mapper")
@SpringBootApplication(scanBasePackages = {"com.llmall.cart", "com.llmall.api"})
public class CartApplication {
    public static void main(String[] args) {
         SpringApplication.run(CartApplication.class, args);
    }
}
