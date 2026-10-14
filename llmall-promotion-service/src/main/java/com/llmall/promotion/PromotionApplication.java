package com.llmall.promotion;

import com.llmall.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 营销域服务：优惠券与秒杀券、领券（一人一单）、券核销、积分发放与积分流水。
 * 数据所有权：llmall-promotion 库。
 */
@EnableFeignClients(basePackages = "com.llmall.api.client", defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.llmall.promotion.mapper")
@SpringBootApplication
public class PromotionApplication {
    public static void main(String[] args) {
        SpringApplication.run(PromotionApplication.class, args);
    }
}
