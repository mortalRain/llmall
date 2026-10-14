package com.llmall.common.config;

import com.llmall.common.domain.PageQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 分页上限的全局装配（配置项 llmall.page.max-size，默认 100）。
 *
 * 【注意】默认值必须是安全值：只有显式调大才放开，避免某个服务漏配时回到 pageSize=-1 拉全表。
 * 用构造函数而非 @PostConstruct 赋值：放在构造期可避免"Bean 未初始化完、请求已按旧值分页"的窗口。
 */
@Configuration
public class PageConfig {

    public PageConfig(@Value("${llmall.page.max-size:100}") Integer maxPageSize) {
        PageQuery.setMaxPageSize(maxPageSize);
    }
}
