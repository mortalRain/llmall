package com.llmall.search;

import com.llmall.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 搜索域服务。
 *
 * 职责：门店检索（关键词 + 类型 + 按热度排序）、搜索热词与建议、搜索历史；
 * 通过事件维护本地搜索视图（读模型），检索路径上不跨服务调用。
 * 数据所有权：llmall-search 库（视图表 shop_search_doc、热词、历史）。
 */
@EnableScheduling
@EnableFeignClients(basePackages = "com.llmall.api.client", defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.llmall.search.mapper")
@SpringBootApplication
public class SearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
