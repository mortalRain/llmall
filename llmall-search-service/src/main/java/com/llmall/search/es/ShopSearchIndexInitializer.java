package com.llmall.search.es;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;

/**
 * 启动时确保 ES 索引存在（幂等）。
 *
 * 搜索是增强能力，不是启动依赖：ES 未启动 / 网络不通 / IK 分词器未装都只 warn 不抛异常。
 *
 * 【注意】"回落 MySQL"的触发条件是 ES 调用抛异常，而不是"索引不存在"：
 * ES 对不存在的索引是返回空结果还是报错，取决于服务端与客户端配置（7.x 默认倾向返回空结果）。
 * 于是存在一个静默窗口：索引已建出但还没有数据（新环境首次部署、或索引被误删后重建）时，
 * 检索可能正常返回空列表而不触发回落，表现为"搜索页什么都搜不到"且不报错。
 * 本类只负责建索引、不负责灌数；遇到该现象请调 POST /search/index/rebuild 重灌读模型。
 * （该结论尚未实机验证：验证方式＝删掉索引 + engine=auto，观察检索是回落 MySQL 还是返回空。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(ElasticsearchOperations.class)
@ConditionalOnProperty(prefix = "llmall.search.es", name = "auto-create-index", havingValue = "true", matchIfMissing = true)
public class ShopSearchIndexInitializer implements ApplicationRunner {

    private final EsShopSearchGateway gateway;

    @Override
    public void run(ApplicationArguments args) {
        if (!gateway.enabled()) {
            // engine=mysql 时 ES 完全不参与：不建索引也不连 ES，避免"没启用却报连不上"的噪音日志
            log.info("[ES] llmall.search.engine 未启用 ES，跳过索引初始化（检索走 MySQL 读模型）");
            return;
        }
        try {
            boolean created = gateway.createIndexIfAbsent();
            long docCount = gateway.count();
            log.info("[ES] 索引检查完成，created={}, 当前文档数={}", created, docCount);
            // 【可观测性·2026-10-15】"索引存在但为空"= 检索返回空结果且不回落 MySQL 的静默状态：
            // 该状态在"新环境首次部署"与"索引被误删后重建"时必然出现，此前它只体现在上面那行 INFO 里，没人会去看。
            // 这里升级为 ERROR 让它可按关键字告警。只加信号，不改检索逻辑（拉取/降级功能本身不补）。
            if (docCount == 0L) {
                log.error("[ES] 索引文档数为 0（created={}）：检索会返回空结果且不会回落 MySQL。"
                        + "若读模型 shop_search_doc 有数据，请调 POST /search/index/rebuild 重灌", created);
            }
        } catch (Exception e) {
            log.warn("[ES] 索引初始化失败（不影响启动；检索会回落 MySQL，修复后调 POST /search/index/rebuild）: {}",
                    e.getMessage());
        }
    }
}
