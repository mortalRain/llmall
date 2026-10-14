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
 * 搜索是增强能力，不是启动依赖：ES 未启动 / 网络不通 / IK 分词器未装都只 warn 不抛异常，
 * 未建索引时检索按 llmall.search.engine 回落（auto → MySQL），
 * 修复后可调 POST /search/index/rebuild 把 MySQL 读模型重灌进 ES。
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
            log.info("[ES] 索引检查完成，created={}, 当前文档数={}", created, gateway.count());
        } catch (Exception e) {
            log.warn("[ES] 索引初始化失败（不影响启动；检索会回落 MySQL，修复后调 POST /search/index/rebuild）: {}",
                    e.getMessage());
        }
    }
}
