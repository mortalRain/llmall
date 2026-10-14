package com.llmall.item.controller;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.utils.BeanUtils;

import com.llmall.item.domain.po.Item;
import com.llmall.item.domain.query.ItemPageQuery;
import com.llmall.item.service.IItemService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Tag(name = "商品管理相关接口")
@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
public class ItemController {

    private final IItemService itemService;

    /**
     * 服务间调用凭证（配置项 {@code llmall.internal.service-token}）：无默认值，配置缺失时一律拒绝（fail-closed），
     * 避免漏配把改库存、改商品、删商品这类管理接口降级成"登录即可调用"的公开接口。
     */
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    /**
     * 校验请求来自本系统内部服务。
     *
     * 【注意】只能用 {@code x-service-token}：{@code x-internal-token} 由网关在认证通过后注入，
     * 任何登录用户的请求都带着它，无法据此区分内外部调用。
     * 【注意】边界只能画在 HTTP 入口：服务间调用走 Feign（带该头），而 MQ 监听器直接调 service，
     * 校验下沉到 service 会把 MQ 回调一起挡掉。
     */
    private void assertInternalCall(String requestToken) {
        if (!StringUtils.hasText(serviceToken)
                || !StringUtils.hasText(requestToken)
                || !serviceToken.equals(requestToken)) {
            throw new ForbiddenException("内部接口，禁止外部调用");
        }
    }

    @Operation(summary = "分页查询商品（支持关键词/分类/门店/类型/价格区间筛选，默认只返回上架商品）")
    @GetMapping("/page")
    // Sentinel 热点资源埋点，规则见 llmall-common 的 SentinelRuleConfig；被限流/熔断时走 blockHandler，返回可读提示而非 500 堆栈
    @SentinelResource(value = "itemPage", blockHandler = "pageQueryBlocked")
    public PageDTO<ItemDTO> queryItemByPage(ItemPageQuery query) {
        return itemService.pageQuery(query);
    }

    /**
     * itemPage 的限流/熔断兜底。
     *
     * 【注意】签名必须与原方法一致、末尾多一个 {@link BlockException}；写错不会在启动时报错，
     * 只在真正触发时抛 {@code Error: block handler not found}。
     */
    public PageDTO<ItemDTO> pageQueryBlocked(ItemPageQuery query, BlockException ex) {
        throw new BizIllegalException("商品列表访问过于频繁，请稍后再试");
    }

    @Operation(summary = "商品分类聚合（商城首页筛选用）")
    @GetMapping("/categories")
    public List<Map<String, Object>> categories(@RequestParam(value = "type", required = false) Integer type) {
        return itemService.listCategories(type);
    }

    @Operation(summary = "根据id批量查询商品")
    @GetMapping
    public List<ItemDTO> queryItemByIds(@RequestParam("ids") List<Long> ids){
        // 匿名可访问的批量查询必须设上限，否则一个请求就能拉全表；100 覆盖结算页/购物车单屏需求
        if (ids.size() > 100) {
            throw new BadRequestException("一次最多查询 100 个商品");
        }
        return itemService.queryItemByIds(ids);
    }

    @Operation(summary = "根据id查询商品")
    @GetMapping("{id}")
    public ItemDTO queryItemById(@PathVariable("id") Long id) {
        // 商品不存在时抛业务错误而非返回 200 + 空 body：调用方拿到 null 只会一路 NPE 到更远的地方（如结算页算出 null 价格）
        Item item = itemService.getById(id);
        if (item == null) {
            throw new BadRequestException("商品不存在: " + id);
        }
        // 已下架/已删除的商品同样不作为可售商品返回：详情页能打开、下单时才失败，比"下架了还显示在售"更糟
        if (item.getStatus() == null || item.getStatus() != 1) {
            throw new BadRequestException("商品已下架: " + id);
        }
        return BeanUtils.copyBean(item, ItemDTO.class);
    }

    @Operation(summary = "新增商品（内部接口，需服务间凭证）")
    @PostMapping
    public void saveItem(@Valid @RequestBody ItemDTO item,
                         @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        // @Valid 不可省：缺少它时 ItemDTO 上的约束全部失效，缺字段/负库存/超长名称要走到 SQL 才失败，用户只看到 500
        itemService.save(BeanUtils.copyBean(item, Item.class));
    }

    @Operation(summary = "更新商品状态（内部接口，需服务间凭证）")
    @PutMapping("/status/{id}/{status}")
    public void updateItemStatus(@PathVariable("id") Long id, @PathVariable("status") Integer status,
                                 @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken){
        assertInternalCall(requestToken);
        // 状态只允许 1 上架 / 2 下架 / 3 删除：不校验时非法值会让商品在上架列表里凭空消失且无任何报错
        if (status == null || (status != 1 && status != 2 && status != 3)) {
            throw new BadRequestException("非法的商品状态: " + status + "（只允许 1 上架 / 2 下架 / 3 删除）");
        }
        Item item = new Item();
        item.setId(id);
        item.setStatus(status);
        itemService.updateById(item);
    }

    @Operation(summary = "更新商品（内部接口，需服务间凭证）")
    @PutMapping
    public void updateItem(@Valid @RequestBody ItemDTO item,
                           @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        // 不允许修改商品状态：置 null 后更新会忽略该字段
        item.setStatus(null);
        // @Valid 同新增接口；本接口为全量更新语义，字段为 null 时静默跳过，超长字符串才报 500
        itemService.updateById(BeanUtils.copyBean(item, Item.class));
    }

    @Operation(summary = "根据id删除商品（内部接口，需服务间凭证）")
    @DeleteMapping("{id}")
    public void deleteItemById(@PathVariable("id") Long id,
                               @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        // 逻辑删除（status=3）而非物理删除：item.id 被订单明细、库存流水引用，物理删除会让历史订单指向不存在的商品
        Item item = new Item();
        item.setId(id);
        item.setStatus(3);
        itemService.updateById(item);
    }

    /**
     * 批量扣减库存。
     *
     * 【注意】集合元素校验必须写在泛型上（{@code List<@Valid OrderDetailDTO>}）：Bean Validation 不会自动
     * 校验集合元素，注解写在 List 上只校验 List 自身，num=-5 会一路穿到 SQL。
     * 权威判定在 ItemServiceImpl 入口 —— 内部 Feign 调用不经过 Spring MVC，本层校验对它没有作用。
     * 【注意】不要在类上加 {@code @Validated}：方法级校验会在 MVC 参数解析前抛
     * {@code ConstraintViolationException}，本该 400 的参数错误会变成 500。
     */
    @Operation(summary = "批量扣减库存（内部接口，需服务间凭证）")
    @PutMapping("/stock/deduct")
    public void deductStock(@RequestBody @Valid List<@Valid OrderDetailDTO> items,
                            @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken){
        // 库存是全网共享的热点数据：匿名可调用即可把任意商品库存清零
        assertInternalCall(requestToken);
        itemService.deductStock(items);
    }

    @Operation(summary = "批量回滚库存（内部接口，需服务间凭证）")
    @PutMapping("/stock/restore")
    public void restoreStock(@RequestBody @Valid List<@Valid OrderDetailDTO> items,
                             @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken){
        assertInternalCall(requestToken);
        itemService.restoreStock(items);
    }

    @Operation(summary = "查询某业务号的库存是否已扣减（内部接口，需服务间凭证）")
    @GetMapping("/stock/deducted")
    public boolean isStockDeducted(@RequestParam("bizId") String bizId,
                                   @RequestParam(value = "bizType", required = false) String bizType,
                                   @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        // 虽是读接口，但它回答的是"钱/货到底动没动"，是补偿链路的判据，同样按内部接口收口（缺凭证直接 403）
        assertInternalCall(requestToken);
        return itemService.isStockDeducted(bizId, bizType);
    }

    @Operation(summary = "查询门店下的套餐/商品")
    @GetMapping("/shop/{shopId}")
    public List<ItemDTO> queryByShopId(@PathVariable("shopId") Long shopId){
        return itemService.listByShopId(shopId);
    }
}
