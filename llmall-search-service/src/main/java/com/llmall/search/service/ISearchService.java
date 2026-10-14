package com.llmall.search.service;

import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.search.domain.po.SearchHistory;
import com.llmall.search.domain.vo.HotWordVO;
import com.llmall.search.domain.vo.SearchEngineStatusVO;
import com.llmall.search.event.ShopDocChangedEvent;

import java.util.List;

/**
 * 搜索域服务。
 *
 * 【注意】数据所有权：只有本服务读写 llmall-search，门店数据由本服务按事件构建本地视图 +
 * 全量重建获得，检索路径上不跨服务调用。
 */
public interface ISearchService {

    /** 门店检索：关键词（名称/商圈/地址）+ 类型过滤，按热度分排序 */
    PageDTO<ShopDTO> search(String keyword, Long typeId, Integer page, Integer size, Long userId);

    /**
     * 附近检索：给定坐标时按距离升序（"距您最近的店"）。
     *
     * 【注意】只有 ES 引擎支持按距离排序（geo_point + geo_distance）；MySQL 回落时退化为普通检索，
     * 结果里的 distance 为空 —— 有意为之，不因 ES 不可用就让附近门店接口整体 500。
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

    /**
     * 消费 shop.hot.changed：增量维护搜索视图。
     *
     * 事件体是整行门店快照（含 sold/status），因此这里做整行 upsert，并用事件时间防乱序。
     *
     * 【注意】幂等闸门（consume_record）由调用方 ShopHotChangedListener 在同一个事务里先落，
     * 保证"这条事件处理过没有"的判据与视图更新同生共死。
     */
    void applyShopHotChanged(ShopDocChangedEvent event);

    /**
     * 对账判据：这条门店变更事件在本域是否已处理（供 llmall-shop-service 的对账任务查询）。
     *
     * 判据为本域 consume_record 里是否存在 (SHOP_HOT_CHANGED, bizId, llmall-search-service) 这一行；
     * 它与视图更新同事务写入，所以"有行"即代表已处理成功。
     */
    boolean isShopHotChangedConsumed(String bizId);

    /** 全量重建搜索视图（首次部署 / 视图损坏时使用），返回同步条数 */
    int rebuildSearchView();

    /** 全量重建 ES 索引（把 MySQL 读模型灌进 ES），返回写入条数；未启用 ES 时返回 0 */
    int rebuildEsIndex();

    /** 检索引擎状态与对账：读模型条数 vs 索引文档数、ES 连通性 */
    SearchEngineStatusVO engineStatus();

    /** 把 Redis 热词落库（定时任务调用），返回落库条数 */
    int syncHotWordsToDb();
}
