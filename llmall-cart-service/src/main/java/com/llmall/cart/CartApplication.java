package com.llmall.cart;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;

@EnableFeignClients(basePackages = "com.llmall.api.client")
@MapperScan("com.llmall.cart.mapper")
@SpringBootApplication(scanBasePackages = {"com.llmall.cart", "com.llmall.api"})
public class CartApplication {
    public static void main(String[] args) {
         SpringApplication.run(CartApplication.class, args);
    }
}
