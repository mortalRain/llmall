package com.llmall.search.service;

import com.llmall.api.dto.ShopDTO;
import com.llmall.api.event.ShopHotChangedEvent;
import com.llmall.common.domain.PageDTO;
import com.llmall.search.domain.po.SearchHistory;
import com.llmall.search.domain.vo.HotWordVO;
import com.llmall.search.domain.vo.SearchEngineStatusVO;

import java.util.List;

/**
 * 搜索域服务。
 *
 * 数据所有权：只有本服务读写 llmall-search；门店数据通过「事件构建的本地视图 + 全量重建」获得，
 * 不在检索路径上跨服务调用。
 */
public interface ISearchService {

    /** 门店检索：关键词（名称/商圈/地址）+ 类型过滤，按热度分排序 */
    PageDTO<ShopDTO> search(String keyword, Long typeId, Integer page, Integer size, Long userId);

    /**
     * 附近检索：给定坐标时按距离升序（"距您最近的店"）。
     *
     * 只有 ES 引擎支持按距离排序（geo_point + geo_distance）；MySQL 回落时退化为普通检索
     * （结果里的 distance 为空）—— 这是**有意为之**：宁愿给"没有距离的搜索结果"，
     * 也不要因为 ES 挂了就让附近门店接口整体 500。
     */
    default PageDTO<ShopDTO> searchNearby(String keyword, Long typeId, Integer page, Integer size,
                                          Double x, Double y, Long userId) {
        return search(keyword, typeId, page, size, userId);
    }


    /** 热词 Top N（Redis ZSet） */
    List<HotWordVO> hotWords(Integer limit);

    /** 搜索建议（按前缀匹配热词） */
    List<String> suggest(String prefix, Integer limit);

    /** 我的搜索历史 */
    List<SearchHistory> myHistory(Long userId, Integer limit);

    /** 清空我的搜索历史 */
    void clearHistory(Long userId);

    /** 消费 shop.hot.changed：增量维护搜索视图（upsert 幂等 + 事件时间防乱序） */
    void applyShopHotChanged(ShopHotChangedEvent event);

    /** 全量重建搜索视图（首次部署 / 视图损坏时使用），返回同步条数 */
    int rebuildSearchView();

    /** 全量重建 ES 索引（把 MySQL 读模型灌进 ES），返回写入条数；未启用 ES 时返回 0 */
    int rebuildEsIndex();

    /** 检索引擎状态与对账：读模型条数 vs 索引文档数、ES 连通性 */
    SearchEngineStatusVO engineStatus();

    /** 把 Redis 热词落库（定时任务调用），返回落库条数 */
    int syncHotWordsToDb();
}
