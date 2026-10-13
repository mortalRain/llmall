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
     * 服务间调用凭证（配置项 {@code llmall.internal.service-token}）。
     * <p>没有默认值：**配置缺失时一律拒绝**（fail-closed）—— 不能因为"运维忘了配"，
     * 就把改库存、改商品、删商品这类管理接口变成"登录即可调用"的公开接口。
     */
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    /**
     * 校验"本请求来自我们自己的服务"。
     *
     * <p>为什么用 {@code x-service-token} 而不是 {@code x-internal-token}：后者由网关在认证通过后
     * 注入，**任何登录用户的请求都带着它**，拿它判"内部调用"等于把用户请求一起放进来。
     * {@code x-service-token} 网关只剥离、不注入（见 AuthGlobalFilter 第 0 步），外部无法伪造。
     *
     * <p>为什么校验放在控制器入口：库存扣减/回滚的服务端调用方是 trade-service 的 Feign
     * （走 HTTP，会带该头），而**MQ 监听器是直接调 service 的**（不经过 HTTP）。
     * 把边界画在 HTTP 入口既能挡掉外部调用，又不影响任何内部链路；反过来下沉到 service
     * 反而会把 MQ 回调一起挡掉。
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
    // Sentinel 热点资源埋点：这是全站最热的读接口，规则见 llmall-common 的 SentinelRuleConfig
    // （QPS 快速失败限流 + 异常比例熔断）。被限流/熔断时走 blockHandler，给出可读提示而不是 500 堆栈。
    @SentinelResource(value = "itemPage", blockHandler = "pageQueryBlocked")
    public PageDTO<ItemDTO> queryItemByPage(ItemPageQuery query) {
        return itemService.pageQuery(query);
    }

    /**
     * itemPage 的限流/熔断兜底。
     *
     * <p>签名必须与原方法一致、并在末尾多一个 {@link BlockException} —— 这是 Sentinel 注解的硬性约定，
     * 写错的话不会报错，只会在真正触发时抛 {@code Error: block handler not found}，
     * 属于"平时看不出、出事时才炸"的典型。
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
        // 匿名可访问的批量查询必须设上限：不设的话一个请求就能拉全表（36 万行商品），
        // 既是廉价的放大攻击面，也会把连接池和带宽打满。100 足够覆盖结算页/购物车单屏需求。
        if (ids.size() > 100) {
            throw new BadRequestException("一次最多查询 100 个商品");
        }
        return itemService.queryItemByIds(ids);
    }

    @Operation(summary = "根据id查询商品")
    @GetMapping("{id}")
    public ItemDTO queryItemById(@PathVariable("id") Long id) {
        // 商品不存在时不能返回 200 + 空 body：调用方拿到 null 只会一路 NPE 到更远的地方
        // （例如结算页算出 null 价格），排查时完全看不出是"商品没了"还是"接口挂了"。
        // 这里直接给出带 id 的业务错误，前端可以把 msg 原样展示给用户。
        Item item = itemService.getById(id);
        if (item == null) {
            throw new BadRequestException("商品不存在: " + id);
        }
        // 已下架/已删除的商品同样不作为可售商品返回：详情页能打开、能加购、
        // 下单时才失败，是比"下架了还显示在售"更糟的体验（用户白填一遍收货信息）。
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
        // 新增：@Valid 让 ItemDTO 上的 @NotBlank/@NotNull/@Size 真正生效。
        // 没有它时，缺 name、负库存、超长名称都要走到 SQL 才失败，用户看到的是
        // 500「服务器内部异常」，而真正的原因（哪个字段不合法）只留在服务端日志里。
        itemService.save(BeanUtils.copyBean(item, Item.class));
    }

    @Operation(summary = "更新商品状态（内部接口，需服务间凭证）")
    @PutMapping("/status/{id}/{status}")
    public void updateItemStatus(@PathVariable("id") Long id, @PathVariable("status") Integer status,
                                 @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken){
        assertInternalCall(requestToken);
        // 状态只允许 1（上架）/2（下架）/3（已删除）三个取值：不校验的话，
        // 一次手滑传 0 或 99 就能让商品在"上架列表"里凭空消失，且没有任何报错。
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
        // 不允许修改商品状态，所以强制设置为null，更新时，就会忽略该字段
        item.setStatus(null);
        // 更新（@Valid 同新增：name/price/stock 必须给出，长度与取值范围同样受约束 ——
        // 否则一次"只想改价格"的调用会把别的字段留空，靠 not_null 静默跳过，
        // 一旦传了超长字符串又是 500，两种结果都不该出现在一个管理接口上）
        itemService.updateById(BeanUtils.copyBean(item, Item.class));
    }

    @Operation(summary = "根据id删除商品（内部接口，需服务间凭证）")
    @DeleteMapping("{id}")
    public void deleteItemById(@PathVariable("id") Long id,
                               @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        assertInternalCall(requestToken);
        // 逻辑删除（status=3）而不是物理删除：item.id 被订单明细、库存流水引用，
        // 物理删掉之后历史订单会指向一个不存在的商品，对账与售后都查不下去。
        Item item = new Item();
        item.setId(id);
        item.setStatus(3);
        itemService.updateById(item);
    }

    /**
     * 批量扣减库存。
     *
     * <p>校验分两层，缺一不可：
     *  ① 这里的 {@code @Valid}（**必须写在泛型元素上** {@code List<@Valid OrderDetailDTO>}）：
     *     Bean Validation 不会自动校验集合里的元素，注解写在 List 上只会校验 List 自身（它没有任何约束），
     *     于是 num=-5 会一路穿到 SQL 里 —— 这是个很容易写错、且写错了完全不报错的地方。
     *     它的作用是尽早给出"数量必须大于 0"这种可读提示。
     *  ② ItemServiceImpl 入口的显式校验：内部 Feign 调用（ItemClient）根本不经过 Spring MVC，
     *     上面那层拦网在服务间调用上完全没有读者，权威判定必须在服务层。
     *  另：这里**不要**在类上加 @Validated —— 那会启用 MethodValidationPostProcessor 的方法级校验，
     *  它在 MVC 参数解析之前抛出 ConstraintViolationException（统一异常处理没有对应 handler），
     *  结果本该是 400 的参数错误会变成 500「服务器内部异常」（实测踩过）。
     */
    @Operation(summary = "批量扣减库存（内部接口，需服务间凭证）")
    @PutMapping("/stock/deduct")
    public void deductStock(@RequestBody @Valid List<@Valid OrderDetailDTO> items,
                            @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken){
        // 库存是全网共享的热点数据：这个接口若能匿名调用，任何人对任意商品发一条请求就能把库存清零。
        assertInternalCall(requestToken);
        itemService.deductStock(items);
    }

    @Operation(summary = "批量回滚库存（内部接口，需服务间凭证）")
    @PutMapping("/stock/restore")
    public void restoreStock(@RequestBody @Valid List<@Valid OrderDetailDTO> items,
                             @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken){
        // 回滚同样是"改库存"：匿名可调用等于给了任何人"凭空加库存"的能力。
        assertInternalCall(requestToken);
        itemService.restoreStock(items);
    }

    @Operation(summary = "查询某业务号的库存是否已扣减（内部接口，需服务间凭证）")
    @GetMapping("/stock/deducted")
    public boolean isStockDeducted(@RequestParam("bizId") String bizId,
                                   @RequestParam(value = "bizType", required = false) String bizType,
                                   @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false) String requestToken) {
        // 虽然只是读，但它回答的是"钱/货 到底动没动"，是补偿链路的判据：被外部误用/探测都不合适，
        // 与扣减、回滚一样按内部接口收口（fail-closed：没带凭证直接 403）
        assertInternalCall(requestToken);
        return itemService.isStockDeducted(bizId, bizType);
    }

    @Operation(summary = "查询门店下的套餐/商品")
    @GetMapping("/shop/{shopId}")
    public List<ItemDTO> queryByShopId(@PathVariable("shopId") Long shopId){
        return itemService.listByShopId(shopId);
    }
}
