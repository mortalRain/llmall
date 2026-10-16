package com.llmall.pay;

import com.llmall.api.config.DefaultFeignConfig;
import com.llmall.api.config.NoRetryFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 支付服务启动类。
 *
 * 【注意】@EnableScheduling 不能删：{@code PayReconcileTask}（支付日终对账）依赖它。
 * 缺了该注解时 @Scheduled 方法一个都不会执行，而且不会有任何报错 ——
 * 表现为"扣款结果未知的支付单永远没人复查"，只能在日志与数据库里靠"什么都没发生"察觉。
 *
 * 【注意】defaultConfiguration 指向 llmall-api 的 DefaultFeignConfig：服务间调用的内部凭证、traceId 透传与 Feign 日志级别由它统一提供。
 * 少了它，扣款/退款/标记已支付这些资金接口会被下游的 fail-closed 校验拒掉（缺服务间凭证头）；
 * 它的日志级别固定为 BASIC 而不是 FULL：请求体里有支付密码，FULL 等于把凭据写进日志。
 */
@EnableScheduling
@EnableFeignClients(basePackages = "com.llmall.api.client", defaultConfiguration = {DefaultFeignConfig.class, NoRetryFeignConfig.class})
@MapperScan("com.llmall.pay.mapper")
@SpringBootApplication
public class PayApplication {
    public static void main(String[] args) {
        SpringApplication.run(PayApplication.class, args);
    }
}