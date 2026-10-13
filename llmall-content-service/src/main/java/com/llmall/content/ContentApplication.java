package com.llmall.content;

import com.llmall.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 内容域服务
 *
 * 职责：探店笔记/评价、评论、点赞（ZSet 排行）、关注关系、Feed 流、图片上传。
 * 数据所有权：llmall-content 库。
 *
 * 关键投递事件：review.created（评价产生）→ llmall-shop-service 更新门店热度。
 *
 * 【@EnableScheduling 是审计 B4 生产端改造新增的】
 * 内容域原本没有任何定时任务，所以没有这个注解。加上 MessageRetryTask（投递失败的退避重发）
 * 之后必须开启调度，否则 @Scheduled 方法**一个都不会执行**，而且不会有任何报错 ——
 * message_log 里 status=3 的行一直躺着、日志里连一条异常都没有，
 * "看起来加了兜底、实际上还是静默断裂"。注解加在本服务的启动类上，
 * 只影响本进程的调度线程池（默认单线程），不会波及其它服务。
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
