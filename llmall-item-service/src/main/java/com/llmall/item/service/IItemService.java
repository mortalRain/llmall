package com.llmall.item.service;
import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.item.domain.po.Item;
import com.llmall.item.domain.query.ItemPageQuery;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 商品（含门店套餐）的库存变更与商城查询。 */
public interface IItemService extends IService<Item> {

    /** 批量扣减库存（带库存充足性校验与幂等流水） */
    void deductStock(List<OrderDetailDTO> items);

    /** 批量回滚库存（订单取消/超时未支付） */
    void restoreStock(List<OrderDetailDTO> items);

    /**
     * 查询某个业务号是否已扣减过库存（以库存流水为准，与幂等键同源）。
     *
     * 【注意】供下单方在事务回滚后判断是否要补库存："扣成功但回包丢失"只有库存服务自己答得上。
     */
    boolean isStockDeducted(String bizId, String bizType);

    List<ItemDTO> listByShopId(Long shopId);

    List<ItemDTO> queryItemByIds(Collection<Long> ids);

    /** 商城列表：支持关键词/分类/门店/类型/价格区间筛选，默认只返回上架商品、按销量倒序 */
    PageDTO<ItemDTO> pageQuery(ItemPageQuery query);

    /** 分类聚合：返回 [{category, count}]，供商城首页的分类筛选用 */
    List<Map<String, Object>> listCategories(Integer type);
}
