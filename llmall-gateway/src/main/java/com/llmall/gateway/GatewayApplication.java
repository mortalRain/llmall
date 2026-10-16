package com.llmall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关服务启动入口：加载 Spring Cloud Gateway，是外部流量的唯一入口。
 *
 * 【注意】本类不含安全逻辑：鉴权由 GlobalFilter 链上的 AuthGlobalFilter 裁决，路由表由 DynamicRouteLoader
 * 从 Nacos 拉取，traceId 由 TraceGlobalFilter 生成。排查"请求被拦"或"请求无路由"要查这些类，不是查本类。
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
