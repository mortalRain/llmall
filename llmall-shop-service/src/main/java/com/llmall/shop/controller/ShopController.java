package com.llmall.shop.controller;

import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.UserContext;
import com.llmall.shop.domain.dto.ShopStaffAddDTO;
import com.llmall.shop.domain.dto.ShopStaffRoleDTO;
import com.llmall.shop.domain.vo.HotReconcileResultVO;
import com.llmall.shop.domain.vo.MyShopStaffVO;
import com.llmall.shop.domain.vo.ShopStaffVO;
import com.llmall.shop.service.IShopFavoriteService;
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

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/**
 * 门店接口。
 *
 * 【注意】鉴权边界与网关 excludePaths 绑定：单段读接口（/shops/{id}、/shops/hot、/shops/page、
 * /shops/nearby、/shops/batch）免鉴权，未登录也能逛门店；多段写/运维接口（/shops/{id}/visit、
 * /shops/{id}/hot/recompute、/shops/cache/**）必须登录，不得把运维入口暴露出去。
 * 收藏的四个接口（/shops/{id}/favorite、/shops/my/favorites）全部是多段路径，均要求登录：
 * 收藏是用户私有数据，不能随门店读接口一起公开。
 */
@Tag(name = "门店相关接口")
@RestController
@RequestMapping("/shops")
@RequiredArgsConstructor
public class ShopController {

    private final IShopService shopService;

    /** 门店员工：只用于"我任职的门店"这一读接口，判权本身由内部接口承担 */
    private final IShopStaffService shopStaffService;

    /** 门店收藏：关系放门店域，收藏列表按 shop_id 回门店表取最新展示字段 */
    private final IShopFavoriteService shopFavoriteService;

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
     * 查询当前登录用户任职的门店与角色。
     *
     * 【用途】前端据此决定展示"核销台"（任意角色）与"员工管理"（仅 OWNER/MANAGER）入口。
     * 【注意】这只是展示开关，不是权限边界：核销由交易域实时调用内部接口判权，
     * 员工管理由下方接口在服务端再次校验，前端伪造 role 不会带来任何权限。
     * 【鉴权】多段路径，网关不白名单放行，未登录请求到不了这里；直连服务端口时
     * UserContext 只会由合法的内部凭证头填充，取不到即 401（fail-closed）。
     */
    @Operation(summary = "我任职的门店与角色（前端用于展示核销台/员工管理入口）")
    @GetMapping("/my/staff")
    public List<MyShopStaffVO> queryMyStaff() {
        return shopStaffService.queryMyStaff(requireLogin());
    }

    /**
     * 门店员工列表。
     *
     * 【权限】仅本店 OWNER/MANAGER；非本店员工（含 STAFF）返回 403。
     * 【注意】路径 /shops/{shopId}/staff 不在网关只读白名单内（/shops/* 只匹配单段），
     * 因此未登录请求在网关即被拦截；controller 里再取一次 UserContext 作为直连端口的兜底。
     */
    @Operation(summary = "门店员工列表（仅店主/店长）")
    @GetMapping("/{shopId}/staff")
    public List<ShopStaffVO> listStaff(@PathVariable("shopId") Long shopId) {
        return shopStaffService.listStaff(shopId, requireLogin());
    }

    /**
     * 新增门店员工。
     *
     * 【权限】本店 OWNER/MANAGER；role=OWNER 仅 OWNER 可授予。
     * 【错误】重复添加返回 409（ConflictException），role 非法返回 400。
     */
    @Operation(summary = "新增门店员工（仅店主/店长；授予店主仅店主）")
    @PostMapping("/{shopId}/staff")
    public void addStaff(@PathVariable("shopId") Long shopId,
                         @RequestBody @Valid ShopStaffAddDTO dto) {
        shopStaffService.addStaff(shopId, requireLogin(), dto.getUserId(), dto.getRole());
    }

    /**
     * 修改门店员工角色。
     *
     * 【为什么有 PUT】POST 是"新增"，重复添加按需求必须 409；改角色若复用 POST，
     * 要么破坏"重复即冲突"的语义，要么让前端只能"先删后加"（删最后一个 OWNER 会被拒）。
     * 单独 PUT 语义清晰，也能独立做权限校验。
     */
    @Operation(summary = "修改门店员工角色（仅店主/店长；授予/撤销店主仅店主）")
    @PutMapping("/{shopId}/staff/{userId}")
    public void updateStaffRole(@PathVariable("shopId") Long shopId,
                                @PathVariable("userId") Long userId,
                                @RequestBody @Valid ShopStaffRoleDTO dto) {
        shopStaffService.updateStaffRole(shopId, requireLogin(), userId, dto.getRole());
    }

    /**
     * 删除门店员工。
     *
     * 【权限】本店 OWNER/MANAGER；删除 OWNER 仅 OWNER 可操作；最后一个 OWNER 不可删除（409）。
     */
    @Operation(summary = "删除门店员工（仅店主/店长；最后一个店主不可删）")
    @DeleteMapping("/{shopId}/staff/{userId}")
    public void removeStaff(@PathVariable("shopId") Long shopId,
                            @PathVariable("userId") Long userId) {
        shopStaffService.removeStaff(shopId, requireLogin(), userId);
    }

    /** 取当前登录人：网关透传 user-info；直连端口取不到时返回 401，而不是把 null 当游客 */
    private Long requireLogin() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        return userId;
    }

    /**
     * 收藏门店（幂等）。
     *
     * 【鉴权】路径为多段（/shops/{id}/favorite），不在网关的只读白名单 /shops/* 内，
     * 未登录请求在网关即被拦截；controller 再取一次 UserContext 作为直连端口的兜底。
     * 【幂等】重复收藏返回 200，不返回 409：前端按钮连点/重试不应被当成失败。
     */
    @Operation(summary = "收藏门店（幂等，重复收藏也返回成功）")
    @PostMapping("/{id}/favorite")
    public void favorite(@PathVariable("id") Long id) {
        shopFavoriteService.addFavorite(id, requireLogin());
    }

    /** 取消收藏（幂等）：未收藏时同样返回成功。 */
    @Operation(summary = "取消收藏门店（幂等，未收藏也返回成功）")
    @DeleteMapping("/{id}/favorite")
    public void unfavorite(@PathVariable("id") Long id) {
        shopFavoriteService.removeFavorite(id, requireLogin());
    }

    /** 当前登录人是否已收藏该门店。 */
    @Operation(summary = "是否已收藏该门店")
    @GetMapping("/{id}/favorite")
    public boolean favoriteStatus(@PathVariable("id") Long id) {
        return shopFavoriteService.isFavorite(id, requireLogin());
    }

    /**
     * 我的收藏门店列表（按收藏时间倒序）。
     *
     * 【注意】路径 /shops/my/favorites 是两段，不会被单段白名单 /shops/* 命中；
     * 与 /shops/my/staff 一样必须登录。
     */
    @Operation(summary = "我的收藏门店（按收藏时间倒序）")
    @GetMapping("/my/favorites")
    public List<ShopDTO> myFavorites() {
        return shopFavoriteService.listMyFavorites(requireLogin());
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
