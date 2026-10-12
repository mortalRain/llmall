package com.llmall.search.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 检索引擎状态 / 对账结果（GET /search/engine）。
 *
 * 为什么要暴露这个接口：引入 ES 之后最大的风险不是"查不到"，而是
 * **读模型与索引静默不一致**（事件丢失、同步失败、手工改库），
 * 表现是"搜索结果莫名其妙少了几个店"，而且没人能第一时间发现。
 * 所以把"读模型条数 vs 索引文档数 + ES 连通性"做成一个可随时查的接口，
 * 对账从"事后猜"变成"一眼看"。
 */
@Data
@Accessors(chain = true)
public class SearchEngineStatusVO {

    /** 配置值：es | mysql | auto */
    private String engine;

    /** 配置层面是否启用 ES（false 时下面几项无意义） */
    private boolean esEnabled;

    /** 真实连通性（会真连一次 ES，不是读缓存） */
    private boolean esHealthy;

    /** MySQL 读模型条数（权威数据，用于对账基准） */
    private long mysqlDocs;

    /** ES 文档数；-1 表示查询失败（不可用） */
    private long esDocs;

    /** 两者是否一致 */
    private boolean consistent;

    /** 补充说明 / 错误信息 */
    private String note;
}
