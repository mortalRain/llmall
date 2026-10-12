package com.llmall.content;

import com.llmall.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 内容域服务
 *
 * 职责：探店笔记/评价、评论、点赞（ZSet 排行）、关注关系、Feed 流、图片上传。
 * 数据所有权：llmall-content 库。
 *
 * 关键投递事件：review.created（评价产生）→ llmall-shop-service 更新门店热度。
 */
@EnableFeignClients(basePackages = "com.llmall.api.client", defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.llmall.content.mapper")
@SpringBootApplication
public class ContentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentApplication.class, args);
    }
}
