package com.llmall.shop.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门店员工列表项：{@code GET /shops/{shopId}/staff}。
 *
 * 只暴露 userId / role / createTime：前端员工管理页需要展示"谁、什么角色、何时加入"，
 * 不需要也不应该暴露内部自增主键 id（跨店唯一性对前端无意义，反而诱导拿 id 当权限凭据）。
 */
@Data
public class ShopStaffVO {

    private Long userId;

    /** OWNER / MANAGER / STAFF */
    private String role;

    private LocalDateTime createTime;
}
