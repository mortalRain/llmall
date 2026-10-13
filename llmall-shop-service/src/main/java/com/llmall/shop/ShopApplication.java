package com.llmall.shop;

import com.llmall.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 门店域服务
 *
 * 职责：门店与门店类型、营业状态、GEO 附近门店、门店热度分与 Redis ZSet 榜单、门店访问统计。
 * 数据所有权：llmall-shop 库，禁止其他服务直接访问。
 *
 * basePackages 里除了共享契约包 com.llmall.api.client，还加了本模块自己的 com.llmall.shop.client：
 * 门店域对账需要问 llmall-search-service"这条事件处理了吗"，而这个 Feign 契约只被门店域使用，
 * 本次改动又不允许改 llmall-api，所以就地声明（见 SearchClient 的类注释）。
 * ⚠️ 漏加这个包的症状是启动即报 "No qualifying bean of type SearchClient"，
 * 因为 @EnableFeignClients 只扫它列出的包。
 *
 * 【@EnableScheduling 是本次新增的，缺了它审计 B4 的兜底等于没做】
 * 原来门店域没有任何定时任务，所以没有这个注解。加上 MessageRetryTask（投递失败重试）
 * 与 ShopSearchReconcileTask（对账补发）之后必须开启调度，否则 @Scheduled 方法
 * **一个都不会执行**：消息投递失败后永远躺在 message_log 里，日志里也不会有任何异常 ——
 * 这正是"看起来加了兜底、实际上还是静默断裂"的典型（实测踩过：改完不加此注解，
 * message_log 里 status 一直停在 1，对账/重试日志一条都没有）。
 */
@EnableScheduling
@EnableFeignClients(basePackages = {"com.llmall.api.client", "com.llmall.shop.client"},
        defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.llmall.shop.mapper")
@SpringBootApplication
public class ShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
