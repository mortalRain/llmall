package com.llmall.api.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * Feign 调用策略：显式禁止自动重试，供服务在自己的 {@code @EnableFeignClients(defaultConfiguration = ...)}
 * 中按类名引用。它不是自动生效的全局配置：没在 defaultConfiguration 里列出的服务拿不到它。
 *
 * 【为什么必须禁止重试】Feign 的重试发生在"连接超时 / IOException"之后，而调用方无法区分两种情况：
 * 1. 请求根本没发出去 —— 重试是安全的；
 * 2. 请求发出去了、对端也执行了，只是响应没回来 —— 重试就是让对端再执行一次副作用。
 * 跨服务的写接口（扣款、扣库存、发券、下单）都属于第 2 种，重试即重复扣款 / 重复下单。
 * 一致性必须由业务侧幂等键保证（balance_flow / stock_flow / consume_record 的唯一键），
 * 而不是靠传输层重试；故把"不重试"写成显式约束，而不是依赖框架当前版本恰好把默认值设为
 * NEVER_RETRY —— 那个默认值是版本相关的事实，升级后可能静默变成"超时重试若干次"。
 *
 * 【为什么放在 llmall-api 集中管】llmall-api 是所有服务共同依赖的 Feign 接口模块，本类放在这里
 * 每个服务都能按类名引用到；放在某个业务服务里，其余服务就引不到，只能各自复制一份，
 * 而复制出来的多份"不重试"约束迟早会有一份被改坏。
 *
 * 【为什么用 defaultConfiguration 而不是逐个 client 配置】defaultConfiguration 一次覆盖该服务的全部
 * Feign 客户端：重试与否是全局一致性策略，不该交给单个 client 自己决定 —— 逐个配置迟早会漏掉一个
 * 写接口，而漏掉的那个就是重复扣款 / 重复下单的入口。
 *
 * 【注意】本类不能加 {@code @Configuration}，也不能放在任何服务会被组件扫描到的包下
 * （故与 {@link DefaultFeignConfig} 一起放在 llmall-api 的 config 包，各服务只按类名引用）。
 * 加了 {@code @Configuration} 会让它变成影响该服务全部 Feign 客户端的全局配置，
 * 这是 Spring Cloud OpenFeign 的既定约定（Feign 配置类由 defaultConfiguration 按客户端装配）。
 */
public class NoRetryFeignConfig {

    /**
     * 禁止自动重试。
     *
     * 【注意】只能用 Bean 声明，不能在 yaml 里写 {@code retryer: feign.Retryer$NeverRetry}：
     * {@code Retryer.NeverRetry} 是内部类实例，Spring 无法从字符串构造，写了会导致上下文启动失败。
     */
    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
