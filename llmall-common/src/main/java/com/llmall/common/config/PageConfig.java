package com.llmall.common.config;

import com.llmall.common.domain.PageQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 分页上限的全局装配（配置项 llmall.page.max-size，默认 100）。
 *
 * <p>为什么做成配置而不是写死常量：演示/压测时可能确实需要更大的单页（例如后端导出），
 * 那时改一行配置重启即可，不用发版；但**默认值必须是安全的那一个** —— 只有显式调大才放开，
 * 不会因为"某个服务忘了配"而回到"pageSize=-1 拉全表"的状态。
 *
 * <p>为什么用构造函数而不是 @PostConstruct：把赋值放在构造期，任何读取都发生在 Bean 可用之后，
 * 不存在"Bean 还没初始化完、请求已经按旧值分页"的窗口。
 */
@Configuration
public class PageConfig {

    public PageConfig(@Value("${llmall.page.max-size:100}") Integer maxPageSize) {
        PageQuery.setMaxPageSize(maxPageSize);
    }
}
