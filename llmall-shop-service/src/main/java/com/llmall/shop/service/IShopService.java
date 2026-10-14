package com.llmall.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.shop.domain.po.Shop;

import java.util.Collection;
import java.util.List;

/**
 * 门店域服务契约。
 *
 * 数据所有权：只有本服务能读写 llmall-shop 库；其他服务要门店数据必须通过 ShopClient（Feign）。
 */
public interface IShopService extends IService<Shop> {

    /** 门店详情 */
    ShopDTO queryById(Long id);

    /** 批量查询门店 */
    List<ShopDTO> queryByIds(Collection<Long> ids);

    /** 分类/关键词分页查询，按热度排序 */
    PageDTO<ShopDTO> pageQuery(Long typeId, String keyword, Integer page, Integer size);

    /** 热度榜单；typeId 为空则查全站榜 */
    List<ShopDTO> queryHotList(Long typeId, Integer limit);

    /** 附近门店，按半径与数量上限筛选 */
    List<ShopDTO> nearby(Double x, Double y, Double radiusKm, Integer limit);

    /** 重算门店热度分并写入 DB 与榜单；返回热度分 */
    double recomputeHotScore(Long shopId);

    /**
     * 评价到达：原子更新门店评价数与平均分、刷新最近评价时间。
     *
     * @return 受影响行数，0 表示门店不存在
     */
    int applyReview(Long shopId, Integer score);

    /**
     * 订单支付成功：累加门店销量并重算热度；供 MQ 消费方调用。
     *
     * @return 受影响行数
     */
    int applyOrderSold(Long shopId, Integer num);

    /** 对账判据：该订单的门店销量是否已处理 */
    boolean isOrderConsumed(Long orderId);

    /** 重建 GEO 索引（首次部署或坐标批量变更后调用），返回写入条数 */
    int rebuildGeoIndex();

    /** 记录一次门店访问（HyperLogLog 统计 UV），userId 为空时按游客处理 */
    void recordVisit(Long shopId, Long userId);

    /** 查询今日 UV 估算值 */
    long queryUv(Long shopId);

    /** 主动失效门店缓存（门店信息变更后调用） */
    void evictCache(Long shopId);

    /** 门店下线/删除时清理派生数据：从热度榜与 GEO 索引摘除、删除详情缓存 */
    void markShopOffline(Long shopId);
}
