import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.content.mapper.FollowMapper;
import com.llmall.content.service.impl.FollowServiceImpl;
import com.llmall.search.es.EsShopSearchGateway;
import com.llmall.search.es.ShopSearchDocument;
import com.llmall.search.es.ShopSearchIndexInitializer;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;

/**
 * 台账 OBS-01 / OBS-02 的「演示一次触发输出」（2026-10-15）。
 *
 * 【为什么要有这个演示器】两个静默缺口加了可观测信号之后，只能证明"代码里有那行日志"，
 * 不能证明"信号真的会触发"。本程序用桩驱动**真实代码路径**，并用 logback 的 ListAppender
 * 把日志抓出来断言 —— 不依赖 ES、不依赖数据库、不需要启动服务。
 *
 * 【为什么不用 JUnit 而用 main】本机 Maven 处于离线模式，本地仓库缺 surefire-junit-platform
 * provider，`mvn test` 无法运行。同一份用例已同时写成 JUnit 形式（ObsSignalDemoTest），
 * 在具备该 provider 的环境里可直接 `mvn test` 运行；本 main 是当前环境下的等价演示入口。
 *
 * 用法（classpath 由两个 fat jar 的 BOOT-INF 拼出，见 run-obs-demo.ps1）
 *   java ObsDemo
 * 退出码 0 = 两个信号都触发成功；1 = 有信号未按预期触发。
 */
public class ObsDemo {

    public static void main(String[] args) throws Exception {
        int failed = 0;
        failed += demoEsEmptyIndex();
        failed += demoFeedOverThreshold();
        System.out.println();
        if (failed == 0) {
            System.out.println("结论: OBS-01 / OBS-02 均按预期触发（演示通过）");
            System.exit(0);
        }
        System.out.println("结论: " + failed + " 个信号未按预期触发（演示失败）");
        System.exit(1);
    }

    // ---------------------------------------------------------------- OBS-02：ES 索引已建但为空

    private static int demoEsEmptyIndex() {
        System.out.println("########## OBS-02：ES 索引已建但文档数为 0 ##########");
        System.out.println("场景：新环境首次部署后的状态 —— 索引建出来了，但还没有灌数据。");
        System.out.println("该状态下检索返回空结果且不回落 MySQL，用户看到搜索页空白，日志里却只有一行 INFO。");

        ListAppender<ILoggingEvent> appender = attach(ShopSearchIndexInitializer.class);
        EsShopSearchGateway stub = new EsShopSearchGateway() {
            @Override public boolean enabled() { return true; }
            @Override public boolean healthy() { return false; }
            @Override public long count() { return 0L; }
            @Override public boolean createIndexIfAbsent() { return false; }
            @Override public PageDTO<ShopDTO> search(String keyword, Long typeId, int page, int size, Double x, Double y) { return null; }
            @Override public void saveDocument(ShopSearchDocument document) { }
            @Override public int saveDocuments(Collection<ShopSearchDocument> documents) { return 0; }
            @Override public int reindexFromReadModel() { return 0; }
        };

        try {
            new ShopSearchIndexInitializer(stub).run(null);
        } catch (Exception e) {
            System.out.println("  [异常] " + e);
        }

        boolean hit = appender.list.stream().anyMatch(e ->
                e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("索引文档数为 0"));
        dump(appender.list,
                e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("索引文档数为 0"));
        System.out.println(hit ? "  >> 结果：ERROR 信号已触发 ✔" : "  >> 结果：ERROR 信号【未触发】✘");
        System.out.println();
        return hit ? 0 : 1;
    }

    // ---------------------------------------------------------------- OBS-01：Feed 超阈值静默跳过

    private static int demoFeedOverThreshold() throws Exception {
        System.out.println("########## OBS-01：Feed 写扩散被阈值跳过 ##########");
        System.out.println("场景：作者粉丝数超过写扩散阈值，本条笔记不会进入任何粉丝的收件箱。");
        System.out.println("此前只有一行 INFO，用户「看不到笔记」这件事在日志里不留痕迹。");

        ListAppender<ILoggingEvent> appender = attach(FollowServiceImpl.class);

        FollowServiceImpl service = new FollowServiceImpl(null, null);
        FollowMapper stubMapper = (FollowMapper) Proxy.newProxyInstance(
                FollowMapper.class.getClassLoader(),
                new Class<?>[]{FollowMapper.class},
                (proxy, method, args) -> "selectCount".equals(method.getName()) ? 999_999L : null);
        Field baseMapper = ServiceImpl.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(service, stubMapper);

        service.pushToFollowers(1L, 42L);

        boolean hit = appender.list.stream().anyMatch(e ->
                e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("写扩散被阈值跳过")
                        && e.getFormattedMessage().contains("不会出现在粉丝 Feed 里"));
        dump(appender.list, e -> e.getLevel() == Level.WARN);
        System.out.println(hit ? "  >> 结果：WARN 信号已触发 ✔（且写明了后果）" : "  >> 结果：WARN 信号【未触发】✘");
        System.out.println();
        return hit ? 0 : 1;
    }

    // ---------------------------------------------------------------- 工具

    private static ListAppender<ILoggingEvent> attach(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void dump(List<ILoggingEvent> events, java.util.function.Predicate<ILoggingEvent> isSignal) {
        System.out.println("  ---- 实际日志输出 ----");
        if (events.isEmpty()) {
            System.out.println("    (无日志)");
        }
        for (ILoggingEvent e : events) {
            String mark = isSignal.test(e) ? " <= 信号" : "";
            System.out.println("    [" + e.getLevel() + "] " + e.getFormattedMessage() + mark);
        }
        System.out.println("  ----------------------");
    }
}
