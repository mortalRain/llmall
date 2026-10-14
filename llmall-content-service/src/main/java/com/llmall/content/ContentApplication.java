package com.llmall.content;

import com.llmall.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 内容域服务：探店笔记/评价、评论、点赞（ZSet 排行）、关注关系、Feed 流、图片上传。
 * 数据所有权：llmall-content 库。
 *
 * 关键投递事件：review.created（评价产生）→ llmall-shop-service 更新门店热度。
 *
 * 【注意】@EnableScheduling 不能删：MessageRetryTask（投递失败的退避重发）依赖它，
 * 缺了 @Scheduled 方法一个都不会执行且不会有任何报错，只能靠 message_log 里 status=3 的行察觉。
 */
@EnableScheduling
@EnableFeignClients(basePackages = "com.llmall.api.client", defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.llmall.content.mapper")
@SpringBootApplication
public class ContentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentApplication.class, args);
    }
}
