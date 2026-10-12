package com.llmall.shop.controller;

import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.utils.UserContext;
import com.llmall.shop.service.IShopService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 门店接口。
 *
 * 路径设计约定（与网关 excludePaths 配合）：
 *  - 访问路径为**单段**的读接口（/shops/{id}、/shops/hot、/shops/page、/shops/nearby、/shops/batch）
 *    在网关配置为免鉴权 —— 本地生活场景中"未登录也能逛门店"是正常产品行为；
 *  - 多段的写/运维接口（/shops/{id}/visit、/shops/{id}/hot/recompute、/shops/cache/**）必须登录，
 *    这样既保证浏览体验，又不会把运维接口暴露出去。
 */
@Tag(name = "门店相关接口")
@RestController
@RequestMapping("/shops")
@RequiredArgsConstructor
public class ShopController {

    private final IShopService shopService;

    /**
     * 运维接口总开关（llmall.maintenance.enabled，默认 true）。
     *
     * 为什么需要它：重建 GEO 索引、重算热度分、失效缓存这类接口是"一人执行、全站受影响"的，
     * 平时必须能用，但一旦怀疑是它们在捣乱（例如索引重建期间查询抖动），
     * 运维要能**立刻一键停掉**，而不是等发版。
     * 这里不做权限体系（项目里没有 RBAC，硬造一个只会更危险），只保证"能被关掉"，
     * 关掉后统一返回 403 + 明确 msg，前端/运维一眼能看出是开关而不是故障。
     */
    @Value("${llmall.maintenance.enabled:false}")
    private boolean maintenanceEnabled;

    private void checkMaintenanceEnabled() {
        if (!maintenanceEnabled) {
            throw new ForbiddenException("运维接口已关闭");
        }
    }

    @Operation(summary = "查询门店详情（走缓存）")
    @GetMapping("/{id}")
    public ShopDTO queryById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    @Operation(summary = "批量查询门店")
    @GetMapping("/batch")
    public List<ShopDTO> queryByIds(@RequestParam("ids") List<Long> ids) {
        return shopService.queryByIds(ids);
    }

    @Operation(summary = "分页查询门店（按热度排序）")
    @GetMapping("/page")
    public PageDTO<ShopDTO> pageQuery(@RequestParam(value = "typeId", required = false) Long typeId,
                                      @RequestParam(value = "keyword", required = false) String keyword,
                                      @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                      @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        return shopService.pageQuery(typeId, keyword, page, size);
    }

    @Operation(summary = "热度榜单（Redis ZSet）")
    @GetMapping("/hot")
    public List<ShopDTO> queryHotList(@RequestParam(value = "typeId", required = false) Long typeId,
                                      @RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit) {
        return shopService.queryHotList(typeId, limit);
    }

    @Operation(summary = "附近门店（Redis GEO）")
    @GetMapping("/nearby")
    public List<ShopDTO> nearby(@RequestParam("x") Double x,
                                @RequestParam("y") Double y,
                                @RequestParam(value = "radius", required = false, defaultValue = "5") Double radiusKm,
                                @RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit) {
        return shopService.nearby(x, y, radiusKm, limit);
    }

    @Operation(summary = "记录门店访问（UV 统计）")
    @PostMapping("/{id}/visit")
    public void visit(@PathVariable("id") Long id) {
        // 登录用户来自网关透传的 user-info；未登录时为 null，按游客统计
        shopService.recordVisit(id, UserContext.getUser());
    }

    @Operation(summary = "查询今日 UV（HyperLogLog 估算）")
    @GetMapping("/{id}/uv")
    public long queryUv(@PathVariable("id") Long id) {
        return shopService.queryUv(id);
    }

    @Operation(summary = "重算门店热度分（评价事件到达后由 MQ 消费方调用）")
    @PostMapping("/{id}/hot/recompute")
    public double recomputeHotScore(@PathVariable("id") Long id) {
        checkMaintenanceEnabled();
        return shopService.recomputeHotScore(id);
    }

    @Operation(summary = "重建 GEO 索引（首次部署/坐标批量变更后调用）")
    @PostMapping("/cache/geo/rebuild")
    public int rebuildGeoIndex() {
        checkMaintenanceEnabled();
        return shopService.rebuildGeoIndex();
    }

    @Operation(summary = "对账用：查询订单的门店销量是否已处理")
    @GetMapping("/orders/{orderId}/consumed")
    public boolean isOrderConsumed(@PathVariable("orderId") Long orderId) {
        return shopService.isOrderConsumed(orderId);
    }

    @Operation(summary = "主动失效门店缓存")
    @DeleteMapping("/cache/{id}")
    public void evictCache(@PathVariable("id") Long id) {
        // 同属"重建/重算"一类的运维动作，共用同一个开关
        checkMaintenanceEnabled();
        shopService.evictCache(id);
    }
}
