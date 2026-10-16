package com.llmall.trade;

import com.llmall.api.config.DefaultFeignConfig;
import com.llmall.api.config.NoRetryFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableFeignClients(basePackages = "com.llmall.api.client", defaultConfiguration = {DefaultFeignConfig.class, NoRetryFeignConfig.class})
@MapperScan("com.llmall.trade.mapper")
@SpringBootApplication
public class TradeApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}