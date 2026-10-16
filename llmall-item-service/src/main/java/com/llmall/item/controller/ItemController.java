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

/**
 * 商品域对外的 HTTP 入口：商品读接口（匿名可访问）+ 商品与库存写接口（仅服务间调用）。
 *
 * 【核心职责】
 * 1. 读：分页列表、分类聚合、按 id 批量查、按 id 单个查、按门店查商品 —— 全部匿名可访问，是商城的公开数据面
 * 2. 写：新增 / 更新 / 改状态 / 逻辑删除商品，批量扣减库存、批量回滚库存 —— 全部要求服务间凭证
 * 3. 判定：{@code GET /items/stock/deducted} 回答"某业务号到底扣没扣过"，是下单方补偿链路的判据
 *
 * 【使用场景】
 * 1. 前端 / 小程序 → 网关 → 本类的 GET 接口；网关只读白名单（见 llmall-gateway 的 {@code llmall.auth.excludePaths}）
 *    只放行 {@code /items}、{@code /items/*}、{@code /items/shop/*}、{@code /items/categories}、{@code /items/page}，
 *    且只对 GET/HEAD/OPTIONS 生效
 * 2. llmall-trade-service 的 Feign 客户端 {@code ItemClient} → 本类的写接口与判定接口。Feign 按服务名直连本服务，
 *    不经过网关，凭证由 {@code DefaultFeignConfig} 统一注入
 * 3. MQ 监听器不经过本类：它们直接调 {@code IItemService}，因此本类的入口校验挡不住也管不着它们
 *
 * 【关键设计】
 * 1. 两道门，互不替代：网关回答"你是谁"（非 GET 一律要求登录态），本类回答"你是不是内部服务"（服务间凭证）。
 *    服务监听 0.0.0.0，绕过网关直连端口时只剩第二道门，所以 {@code assertInternalCall} 不能省
 * 2. 入口校验只做"防呆"，权威校验在 {@code ItemServiceImpl.validateDetailItems}：Feign 调用不经过 Spring MVC，
 *    本层的 {@code @Valid} 对它没有约束力
 * 3. 读接口按商品状态收口：单个查询要求 status=1；批量查询刻意不按状态过滤，风险见 {@code queryItemByIds}
 *
 * 【注意】匿名可访问的读接口是公开数据面，写接口是资产变更面，两者边界由本类与网关两侧共同把守。
 * 【注意】写接口一旦去掉 {@code assertInternalCall}，任何人一个请求就能把任意商品库存改掉或清零；
 * 反过来把它下沉到 Service 层，又会把直接调 service 的 MQ 回调一起挡掉（原因见该方法注释）。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
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

    /**
     * 商品分页列表（匿名可访问）。
     *
     * 【使用场景】
     * 商城首页、搜索结果页、门店套餐页的唯一列表入口；三个页面共用同一套筛选参数，靠不同入参组合区分场景。
     * 本接口是公网暴露面最大的读接口，因此挂了 Sentinel 资源 {@code itemPage} 做限流。
     *
     * 【入参约束】见 {@link ItemPageQuery}，全部可选，越界不报错而是"收敛"：
     * 1. {@code pageNo}：小于 1 或缺失按第 1 页处理（不返回 400）
     * 2. {@code pageSize}：小于 1 按默认 20 条；超过上限时被夹到上限（默认 100，可由 {@code llmall.page.max-size} 覆盖）。
     *    这是防"一次拉走整张表"的闸门，不是参数校验 —— 非法分页参数不该让前端翻页组件整页报错
     * 3. {@code sortBy}：只接受白名单列（sold / price / comment_count / create_time / update_time / id），
     *    未命中一律回落到 {@code sold} 倒序。它是被拼进 ORDER BY 的自由字符串，白名单即注入防护
     * 4. {@code status}：不传默认只查上架（1）；传 2/3 可查出下架/已删除商品，属管理场景预留
     * 5. {@code minPrice} / {@code maxPrice}：单位分；只写一侧即单边过滤
     *
     * 【返回】{@link PageDTO} 包裹的 {@code List<ItemDTO>}；无数据时返回空列表而非 null。
     *
     * 【异常】
     * 1. 400（BadRequest）：{@code pageNo} / {@code pageSize} 类型不是整数、{@code minPrice} 等传了非数字
     * 2. 400（BadRequest）：触发 Sentinel 限流或熔断 —— 由 {@link #pageQueryBlocked} 抛出，文案为"访问过于频繁"
     * 3. 500（InternalServerError）：数据库不可用等真实故障
     *
     * 【兼容性】筛选参数只增不减；新增筛选参数时前端无需同步调整。
     */
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
        // @Valid 同新增接口：name/price/stock 为必填，缺任一即 400；其余字段为 null 时不参与 SQL（update-strategy=not_null）
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
