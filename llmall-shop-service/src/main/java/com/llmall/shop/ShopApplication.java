package com.llmall.shop;

import com.llmall.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 门店域服务。
 *
 * 职责：门店与门店类型、营业状态、GEO 附近门店、门店热度分与 Redis ZSet 榜单、门店访问统计。
 * 数据所有权：llmall-shop 库，禁止其他服务直接访问。
 *
 * 【注意】@EnableFeignClients 的 basePackages 除共享契约包 com.llmall.api.client 外，必须包含本模块
 * 自己的 com.llmall.shop.client（对账用的 Feign 契约，见 SearchClient），
 * 漏加的症状是启动即报 "No qualifying bean of type SearchClient"。
 * 【注意】@EnableScheduling 缺失时所有 @Scheduled 方法一个都不会执行，消息重试与对账补发会静默失效。
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
