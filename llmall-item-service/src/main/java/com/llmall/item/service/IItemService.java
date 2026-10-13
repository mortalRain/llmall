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

/**
 * <p>
 * 商品表 服务类
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface IItemService extends IService<Item> {

    /** 批量扣减库存（带库存充足性校验与幂等流水） */
    void deductStock(List<OrderDetailDTO> items);

    /** 批量回滚库存（订单取消/超时未支付） */
    void restoreStock(List<OrderDetailDTO> items);

    /**
     * 查询某个业务号是否已经扣减过库存（读的是库存流水，权威且与幂等键同源）。
     *
     * <p>供下单方在事务回滚后判断"要不要补库存"：扣库存是跨服务调用，
     * "扣成功但回包丢失"会让下单方以为没扣、而库存其实少了 —— 只有库存服务自己能回答这个问题。
     */
    boolean isStockDeducted(String bizId, String bizType);

    /** 查询门店下的套餐/商品 */
    List<ItemDTO> listByShopId(Long shopId);

    List<ItemDTO> queryItemByIds(Collection<Long> ids);

    /** 商城列表：支持关键词/分类/门店/类型/价格区间筛选，默认只返回上架商品、按销量倒序 */
    PageDTO<ItemDTO> pageQuery(ItemPageQuery query);

    /** 分类聚合：返回 [{category, count}]，供商城首页的分类筛选用 */
    List<Map<String, Object>> listCategories(Integer type);
}
