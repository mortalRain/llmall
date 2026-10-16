package com.llmall.search.es;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 台账 OBS-02 的可观测信号演示（2026-10-15）：索引"存在但为空"时必须打出 ERROR。
 *
 * 【为什么要这个测试】该缺口此前是静默的：索引建出来了但没数据，检索返回空结果且不回落 MySQL，
 * 用户看到"搜索页什么都搜不到"而日志里只有一行 INFO。信号加完不算完成 —— 必须证明它真的会触发。
 *
 * 【怎么做到不依赖 ES】EsShopSearchGateway 是接口，本测试用桩实现驱动真实代码路径；
 * 日志用 logback 的 ListAppender 捕获后断言，而不是靠人肉看控制台。
 */
class ObsSignalDemoTest {

    /** 桩网关：启用 ES、索引已存在（created=false），但文档数为 0 —— 正是"新环境首次部署"后的状态 */
    private EsShopSearchGateway emptyIndexGateway() {
        return new EsShopSearchGateway() {
            @Override public boolean enabled() { return true; }
            @Override public boolean healthy() { return false; }
            @Override public long count() { return 0L; }
            @Override public boolean createIndexIfAbsent() { return false; }
            @Override public PageDTO<ShopDTO> search(String keyword, Long typeId, int page, int size, Double x, Double y) { return null; }
            @Override public void saveDocument(ShopSearchDocument document) { }
            @Override public int saveDocuments(Collection<ShopSearchDocument> documents) { return 0; }
            @Override public int reindexFromReadModel() { return 0; }
        };
    }

    private ListAppender<ILoggingEvent> attachAppender(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    void emptyIndexMustEmitErrorSignal() {
        ListAppender<ILoggingEvent> appender = attachAppender(ShopSearchIndexInitializer.class);

        new ShopSearchIndexInitializer(emptyIndexGateway()).run(null);

        // 打印实际输出，作为"演示一次触发"的证据
        System.out.println("===== OBS-02 触发输出 =====");
        appender.list.forEach(e -> System.out.println("  [" + e.getLevel() + "] " + e.getFormattedMessage()));
        System.out.println("===========================");

        assertThat(appender.list)
                .as("索引文档数为 0 时必须打出 ERROR 信号")
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("索引文档数为 0"));
    }

    @Test
    void nonEmptyIndexMustNotEmitErrorSignal() {
        ListAppender<ILoggingEvent> appender = attachAppender(ShopSearchIndexInitializer.class);

        EsShopSearchGateway stub = new EsShopSearchGateway() {
            @Override public boolean enabled() { return true; }
            @Override public boolean healthy() { return true; }
            @Override public long count() { return 128L; }
            @Override public boolean createIndexIfAbsent() { return false; }
            @Override public PageDTO<ShopDTO> search(String keyword, Long typeId, int page, int size, Double x, Double y) { return null; }
            @Override public void saveDocument(ShopSearchDocument document) { }
            @Override public int saveDocuments(Collection<ShopSearchDocument> documents) { return 0; }
            @Override public int reindexFromReadModel() { return 0; }
        };
        new ShopSearchIndexInitializer(stub).run(null);

        // 反向用例：索引有数据时不能误报，否则信号会被噪音淹没（闸门设计的老问题）
        assertThat(appender.list)
                .as("索引非空时不应打出 ERROR")
                .noneMatch(e -> e.getLevel() == Level.ERROR);
    }
}
