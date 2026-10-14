package com.llmall.search.es;

import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;

import java.util.Collection;

/**
 * ES 检索网关：把"ES 怎么查/怎么写"收口在一处，业务层只关心"要不要用 ES、失败了怎么办"。
 *
 * 单独抽一层是为了让现有 MySQL 检索逻辑原样保留作为回落，两种引擎能在同一份业务代码里按
 * llmall.search.engine 切换对照；网关内部异常直接外抛，由业务层决定是否回落。
 */
public interface EsShopSearchGateway {

    /** 配置层面是否启用 ES（llmall.search.engine = es / auto） */
    boolean enabled();

    /** 真连一次 ES 探活（用于状态接口与回落判断），不做缓存 */
    boolean healthy();

    /** 索引文档数（与 MySQL 读模型条数对比即为"对账"） */
    long count();

    /** 建索引（不存在时），返回是否真的创建了 */
    boolean createIndexIfAbsent();

    /**
     * 检索：关键词（名称/类型/商圈/地址）+ 类型过滤，function_score 综合排序。
     *
     * @param x 经度，可选；与 y 同时给出时按距离升序（"附近"场景），否则按综合得分排序
     * @param y 纬度，可选
     */
    PageDTO<ShopDTO> search(String keyword, Long typeId, int page, int size, Double x, Double y);

    /** 写入/覆盖单个文档（幂等：以 shopId 为主键） */
    void saveDocument(ShopSearchDocument document);

    /** 批量写入（全量重建用） */
    int saveDocuments(Collection<ShopSearchDocument> documents);

    /** 用 MySQL 读模型全量重建索引，返回写入条数 */
    int reindexFromReadModel();
}
