package com.llmall.search.support;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 秒精度会静默丢版本；禁止在未执行66迁移的数据库上启动新版搜索服务。 */
@Component
@RequiredArgsConstructor
public class SearchSyncSchemaGuard implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;
    // 在SmartLifecycle（含Rabbit监听器）启动前校验，不能等ApplicationRunner才发现版本精度不足。
    @Override public void afterSingletonsInstantiated() {
        Integer precision = jdbc.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shop_search_doc' AND COLUMN_NAME='event_time'", Integer.class);
        if (precision == null || precision < 3) {
            throw new IllegalStateException("搜索事件版本需要毫秒精度，请先执行sql/merged/66-search-event-precision.sql");
        }
    }
}
