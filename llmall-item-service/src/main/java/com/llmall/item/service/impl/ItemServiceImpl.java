package com.llmall.item.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.CollUtils;
import com.llmall.item.domain.po.Item;
import com.llmall.item.domain.po.StockFlow;
import com.llmall.item.domain.query.ItemPageQuery;
import com.llmall.item.mapper.ItemMapper;
import com.llmall.item.mapper.StockFlowMapper;
import com.llmall.item.service.IItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 商品（实物商品 / 门店套餐）查询，以及下单扣减库存、取消回滚库存。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    /** 库存流水的业务类型：下单扣减 / 取消回滚 */
    private static final String BIZ_TYPE_DEDUCT = "ORDER_CREATED";
    private static final String BIZ_TYPE_RESTORE = "ORDER_CANCELED";

    /** 商品状态：1 上架 */
    private static final int STATUS_ON_SALE = 1;

    private final StockFlowMapper stockFlowMapper;

    /** 扣减库存：充足性交给 SQL 的 stock >= num 原子判断（按受影响行数判定，杜绝超卖），整批同事务，任一商品不足则全部回滚。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductStock(List<OrderDetailDTO> items) {
        if (CollUtils.isEmpty(items)) {
            return;
        }
        validateDetailItems(items);
        for (OrderDetailDTO dto : items) {
            String bizType = StrUtil.blankToDefault(dto.getBizType(), BIZ_TYPE_DEDUCT);
            // 1.【幂等闸门】先写流水，插入成功才允许扣减（详见 claimFlow）
            if (!claimFlow(dto, bizType, -Math.abs(dto.getNum()))) {
                log.info("库存已扣减过，幂等跳过: bizType={}, bizId={}, itemId={}",
                        bizType, dto.getBizId(), dto.getItemId());
                continue;
            }
            // 2.再改库存：闸门与扣减在同一事务内，任一失败（含库存不足）整体回滚，流水不残留，消息重投后仍可正常扣减
            int rows = baseMapper.deductStock(dto);
            if (rows == 0) {
                // rows=0 只可能是 stock < num 或 num <= 0（id 不存在同样为 0，也属非法请求）
                throw new BizIllegalException("库存不足，商品 id=" + dto.getItemId());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreStock(List<OrderDetailDTO> items) {
        if (CollUtils.isEmpty(items)) {
            return;
        }
        // 与 deductStock 同序：先占流水再改库存；此处 SQL 是 stock + #{num}，num 为负会反向扣库存并把 sold 抬高，入口校验同样必要
        validateDetailItems(items);
        for (OrderDetailDTO dto : items) {
            String bizType = StrUtil.blankToDefault(dto.getBizType(), BIZ_TYPE_RESTORE);
            if (!claimFlow(dto, bizType, Math.abs(dto.getNum()))) {
                log.info("库存已回滚过，幂等跳过: bizType={}, bizId={}, itemId={}",
                        bizType, dto.getBizId(), dto.getItemId());
                continue;
            }
            int rows = baseMapper.restoreStock(dto);
            if (rows == 0) {
                throw new BizIllegalException("回滚库存失败，商品不存在: id=" + dto.getItemId());
            }
        }
    }

    /**
     * 入口显式校验：@Min/@NotNull 只在经 Spring MVC 参数绑定 + @Validated 的路径上生效，而这两个接口的
     * 消费者是 llmall-trade-service 的 Feign 调用，注解在链路上没有读者。
     * 【注意】num 必须为正：为负时 SQL 的 stock >= num 恒真、stock = stock - num 反而加库存，防超卖守卫失效。
     */
    private void validateDetailItems(List<OrderDetailDTO> items) {
        for (OrderDetailDTO dto : items) {
            if (dto == null || dto.getItemId() == null || dto.getNum() == null || dto.getNum() <= 0) {
                throw new BizIllegalException("库存变动的商品与数量非法");
            }
        }
    }

    @Override
    public boolean isStockDeducted(String bizId, String bizType) {
        if (StrUtil.isBlank(bizId)) {
            return false;
        }
        // 与扣减写流水的键同源（biz_type + biz_id），"查得到流水"即"确实扣过"；刻意不查 item 库存数：库存会被别的链路改变，流水才是不可变证据
        String type = StrUtil.blankToDefault(bizType, BIZ_TYPE_DEDUCT);
        Long count = stockFlowMapper.selectCount(new LambdaQueryWrapper<StockFlow>()
                .eq(StockFlow::getBizType, type)
                .eq(StockFlow::getBizId, bizId));
        return count != null && count > 0;
    }

    @Override
    public List<ItemDTO> listByShopId(Long shopId) {
        List<Item> items = lambdaQuery()
                .eq(Item::getShopId, shopId)
                .eq(Item::getStatus, 1)
                .list();
        return BeanUtils.copyList(items, ItemDTO.class);
    }

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        return BeanUtils.copyList(listByIds(ids), ItemDTO.class);
    }

    /** 可排序白名单：sortBy 是前端自由字符串且会被拼进 ORDER BY，不命中白名单一律回落到默认列，避免 sortBy=price;DROP TABLE 这类注入 */
    private static final Set<String> SORTABLE = new HashSet<>(Arrays.asList(
            "sold", "price", "comment_count", "create_time", "update_time", "id"));

    @Override
    public PageDTO<ItemDTO> pageQuery(ItemPageQuery query) {
        String sortBy = query.getSortBy();
        if (StrUtil.isBlank(sortBy) || !SORTABLE.contains(sortBy)) {
            query.setSortBy("sold");
            if (query.getIsAsc() == null) {
                query.setIsAsc(false);
            }
        }
        Page<Item> page = query.toMpPage("sold", false);
        LambdaQueryWrapper<Item> qw = new LambdaQueryWrapper<Item>()
                .eq(Item::getStatus, query.getStatus() == null ? STATUS_ON_SALE : query.getStatus())
                .eq(query.getType() != null, Item::getType, query.getType())
                .eq(query.getShopId() != null, Item::getShopId, query.getShopId())
                .eq(StrUtil.isNotBlank(query.getCategory()), Item::getCategory, query.getCategory())
                .eq(StrUtil.isNotBlank(query.getBrand()), Item::getBrand, query.getBrand())
                .like(StrUtil.isNotBlank(query.getKey()), Item::getName, query.getKey())
                .ge(query.getMinPrice() != null, Item::getPrice, query.getMinPrice())
                .le(query.getMaxPrice() != null, Item::getPrice, query.getMaxPrice());
        page(page, qw);
        return PageDTO.of(page, ItemDTO.class);
    }

    @Override
    public List<Map<String, Object>> listCategories(Integer type) {
        // 交给 SQL group by 聚合出分类与数量，避免把几万行商品拉到内存里再统计
        QueryWrapper<Item> qw = new QueryWrapper<>();
        qw.select("category", "COUNT(*) AS count")
                .eq("status", STATUS_ON_SALE)
                .eq(type != null, "type", type)
                .isNotNull("category").ne("category", "")
                .groupBy("category")
                .orderByDesc("count");
        return listMaps(qw);
    }

    /**
     * 幂等闸门：先写流水，插入成功才允许执行库存变更，唯一键 (biz_type, biz_id, item_id) 就是闸门本身。
     *
     * 【注意】不能改成"先查流水再扣减"：查询与插入之间无互斥，并发（MQ 重投、Feign 重试、双击下单）下
     * 两个线程都会查到 0 并各扣一次库存。也不把 DuplicateKeyException 抛出：插入冲突是重复投递的正常分支，
     * 被捕获后事务不会被标记 rollback-only（只有异常逃出 @Transactional 代理才会），同事务内后续商品可继续处理。
     *
     * @return true 闸门通过并已写流水占位；false 该业务已处理过，跳过
     */
    private boolean claimFlow(OrderDetailDTO dto, String bizType, int changeNum) {
        if (StrUtil.isBlank(dto.getBizId())) {
            // 无 bizId 即无幂等判据（唯一键里的 biz_id 为空无法构成同一笔业务），不静默跳过，
            // 而是打 WARN 暴露漏传 bizId 的调用方，避免其误以为本次已受幂等保护
            log.warn("库存变动未传 bizId，本次操作【未启用幂等保护】: bizType={}, itemId={}, num={}",
                    bizType, dto.getItemId(), dto.getNum());
            return true;
        }
        StockFlow flow = new StockFlow()
                .setItemId(dto.getItemId())
                .setShopId(dto.getShopId())
                .setChangeNum(changeNum)
                .setBizType(bizType)
                .setBizId(dto.getBizId())
                .setStatus(1);
        try {
            stockFlowMapper.insert(flow);
            return true;
        } catch (DuplicateKeyException e) {
            // 唯一键 uk_biz(biz_type, biz_id, item_id) 命中 → 该业务在本地已处理过
            return false;
        }
    }
}
