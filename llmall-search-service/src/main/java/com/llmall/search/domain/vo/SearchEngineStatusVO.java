package com.llmall.search.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 检索引擎状态与对账结果（GET /search/engine）。
 *
 * 【注意】ES 上线后最大的风险不是查不到，而是读模型与索引静默不一致（事件丢失、同步失败、手工改库），
 * 表现为搜索结果莫名少几个店且无人第一时间发现，故把"读模型条数 vs 索引文档数 + 连通性"做成随时可查的接口。
 */
@Data
@Accessors(chain = true)
public class SearchEngineStatusVO {

    /** 配置值：es | mysql | auto */
    private String engine;

    /** 配置是否启用 ES；false 时下面几项无意义 */
    private boolean esEnabled;

    /** 真实连通性，会真连一次 ES，不是读缓存 */
    private boolean esHealthy;

    /** MySQL 读模型条数，权威口径，用于对账基准 */
    private long mysqlDocs;

    /** ES 文档数；-1 表示查询失败（不可用） */
    private long esDocs;

    private boolean consistent;

    private String note;
}
