package com.llmall.shop;

import com.llmall.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 门店域服务
 *
 * 职责：门店与门店类型、营业状态、GEO 附近门店、门店热度分与 Redis ZSet 榜单、门店访问统计。
 * 数据所有权：llmall-shop 库，禁止其他服务直接访问。
 */
@EnableFeignClients(basePackages = "com.llmall.api.client", defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.llmall.shop.mapper")
@SpringBootApplication
public class ShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
