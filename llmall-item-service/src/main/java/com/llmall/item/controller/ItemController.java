package com.llmall.item.controller;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.utils.BeanUtils;

import com.llmall.item.domain.po.Item;
import com.llmall.item.domain.query.ItemPageQuery;
import com.llmall.item.service.IItemService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
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

    @Operation(summary = "分页查询商品（支持关键词/分类/门店/类型/价格区间筛选，默认只返回上架商品）")
    @GetMapping("/page")
    public PageDTO<ItemDTO> queryItemByPage(ItemPageQuery query) {
        return itemService.pageQuery(query);
    }

    @Operation(summary = "商品分类聚合（商城首页筛选用）")
    @GetMapping("/categories")
    public List<Map<String, Object>> categories(@RequestParam(value = "type", required = false) Integer type) {
        return itemService.listCategories(type);
    }

    @Operation(summary = "根据id批量查询商品")
    @GetMapping
    public List<ItemDTO> queryItemByIds(@RequestParam("ids") List<Long> ids){
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

    @Operation(summary = "新增商品")
    @PostMapping
    public void saveItem(@Valid @RequestBody ItemDTO item) {
        // 新增：@Valid 让 ItemDTO 上的 @NotBlank/@NotNull/@Size 真正生效。
        // 没有它时，缺 name、负库存、超长名称都要走到 SQL 才失败，用户看到的是
        // 500「服务器内部异常」，而真正的原因（哪个字段不合法）只留在服务端日志里。
        itemService.save(BeanUtils.copyBean(item, Item.class));
    }

    @Operation(summary = "更新商品状态")
    @PutMapping("/status/{id}/{status}")
    public void updateItemStatus(@PathVariable("id") Long id, @PathVariable("status") Integer status){
        Item item = new Item();
        item.setId(id);
        item.setStatus(status);
        itemService.updateById(item);
    }

    @Operation(summary = "更新商品")
    @PutMapping
    public void updateItem(@Valid @RequestBody ItemDTO item) {
        // 不允许修改商品状态，所以强制设置为null，更新时，就会忽略该字段
        item.setStatus(null);
        // 更新（@Valid 同新增：name/price/stock 必须给出，长度与取值范围同样受约束 ——
        // 否则一次"只想改价格"的调用会把别的字段留空，靠 not_null 静默跳过，
        // 一旦传了超长字符串又是 500，两种结果都不该出现在一个管理接口上）
        itemService.updateById(BeanUtils.copyBean(item, Item.class));
    }

    @Operation(summary = "根据id删除商品")
    @DeleteMapping("{id}")
    public void deleteItemById(@PathVariable("id") Long id) {
        itemService.removeById(id);
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
    @Operation(summary = "批量扣减库存")
    @PutMapping("/stock/deduct")
    public void deductStock(@RequestBody @Valid List<@Valid OrderDetailDTO> items){
        itemService.deductStock(items);
    }

    @Operation(summary = "批量回滚库存（订单取消/超时未支付）")
    @PutMapping("/stock/restore")
    public void restoreStock(@RequestBody @Valid List<@Valid OrderDetailDTO> items){
        itemService.restoreStock(items);
    }

    @Operation(summary = "查询门店下的套餐/商品")
    @GetMapping("/shop/{shopId}")
    public List<ItemDTO> queryByShopId(@PathVariable("shopId") Long shopId){
        return itemService.listByShopId(shopId);
    }
}
