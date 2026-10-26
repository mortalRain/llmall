package com.llmall.shop.controller;

import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.UserContext;
import com.llmall.shop.domain.vo.HotReconcileResultVO;
import com.llmall.shop.service.IShopService;
import com.llmall.shop.service.IShopStaffService;
import com.llmall.shop.task.ShopStatRollupTask;
import com.llmall.shop.task.ShopSearchReconcileTask;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 门店接口。
 *
 * 【注意】鉴权边界与网关 excludePaths 绑定：单段读接口（/shops/{id}、/shops/hot、/shops/page、
 * /shops/nearby、/shops/batch）免鉴权，未登录也能逛门店；多段写/运维接口（/shops/{id}/visit、
 * /shops/{id}/hot/recompute、/shops/cache/**）必须登录，不得把运维入口暴露出去。
 */
@Tag(name = "门店相关接口")
@RestController
@RequestMapping("/shops")
@RequiredArgsConstructor
public class ShopController {

    private final IShopService shopService;

    /** 门店员工：只用于"我任职的门店"这一读接口，判权本身由内部接口承担 */
    private final IShopStaffService shopStaffService;

    /** 对账任务：手动触发入口（定时触发见 ShopSearchReconcileTask#scheduledReconcile） */
    private final ShopSearchReconcileTask shopSearchReconcileTask;

    /** 访问统计落库任务：手动触发入口（定时触发见 ShopStatRollupTask#scheduledRollup） */
    private final ShopStatRollupTask shopStatRollupTask;

    /**
     * 运维接口总开关（llmall.maintenance.enabled，application.yaml 中默认 true）。
     *
     * 【注意】重建 GEO 索引、重算热度分、失效缓存属"一人执行、全站受影响"的动作，必须能一键停掉而非等发版；
     * 关闭后统一返回 403，便于一眼区分是开关还是故障。项目无 RBAC，不为此另造权限体系。
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

    /**
     * 查询当前登录用户任职的门店 id 列表。
     *
     * 【用途】前端订单页据此决定是否展示"去核销"入口（非本店员工隐藏并提示）。
     * 【注意】这只是展示开关，不是权限边界：真正的拦截在交易域的核销接口（它实时调用
     * {@link InternalShopController} 判权），前端拿到伪造的 id 也核销不了。
     * 【鉴权】多段路径，网关不白名单放行，未登录请求到不了这里；直连服务端口时
     * UserContext 只会由合法的内部凭证头填充，取不到即 401（fail-closed）。
     */
    @Operation(summary = "我任职的门店 id 列表（前端用于展示核销入口）")
    @GetMapping("/my/staff")
    public List<Long> queryMyStaffShopIds() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        return shopStaffService.queryStaffShopIds(userId);
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

    @Operation(summary = "对账：检查已投递的门店热度事件搜索域处理了没有，明确未处理则补发（审计 B4，运维/排障用）")
    @PostMapping("/hot/reconcile")
    public HotReconcileResultVO reconcileHotChanged() {
        // 与其它运维接口共用开关：手动对账会请求下游并可能触发补发，属"一人执行、全站受影响"的动作。
        // 手动入口的另一用途是排障与验证：定时对账默认 5 分钟一轮，且需要一个可重复触发的入口确认补发是否真的发生。
        checkMaintenanceEnabled();
        return shopSearchReconcileTask.reconcile();
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
        checkMaintenanceEnabled();
        shopService.evictCache(id);
    }

    @Operation(summary = "门店下线：清理详情缓存 + 热度榜成员 + GEO 坐标（门店下架/删除后调用）")
    @DeleteMapping("/cache/{id}/offline")
    public void markOffline(@PathVariable("id") Long id) {
        checkMaintenanceEnabled();
        shopService.markShopOffline(id);
    }

    /**
     * 门店访问统计落库：把 Redis 里的 UV/PV 落到 shop_stat。
     *
     * 【注意】带 date 参数时直接落该日期，用于演示与排障 —— 定时任务是凌晨落"前一天"的数据，
     * 没有这个入口就只能等到第二天才能确认落库是否真的发生。不带参数则等同于定时任务的行为。
     *
     * @param date 要落库的统计日期（可选，默认前一天）
     * @return 实际写入的门店数
     */
    @Operation(summary = "门店访问统计落库（运维/排障用）：UV/PV 从 Redis 落到 shop_stat")
    @PostMapping("/stat/rollup")
    public int rollupVisitStat(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        checkMaintenanceEnabled();
        return date == null
                ? shopStatRollupTask.rollupYesterday()
                : shopStatRollupTask.rollup(date);
    }
}
