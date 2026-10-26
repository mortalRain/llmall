package com.llmall.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.shop.domain.po.ShopStaff;

import java.util.List;

/**
 * 门店员工服务：核销权限判定的唯一入口。
 *
 * 【权限口径】只有"该订单所属门店的员工"才能核销该门店的订单；判定必须由本服务（数据所有者）
 * 回答，交易域通过内部接口 {@code GET /internal/shops/{shopId}/staff/{userId}} 查询，
 * 不得在交易域复制一份员工表 —— 两处数据一旦漂移就会出现"员工离职了还能核销"。
 */
public interface IShopStaffService extends IService<ShopStaff> {

    /**
     * 判断用户是否为指定门店的员工。
     *
     * 【fail-closed】任一侧 id 为空返回 false（不查库），绝不返回 true 兜底：
     * 判权接口的任何"猜"都是越权。
     *
     * @param shopId 门店 id
     * @param userId 用户 id
     * @return true 仅当 shop_staff 中存在 (shopId, userId) 这一行
     */
    boolean isStaff(Long shopId, Long userId);

    /**
     * 查询用户任职的全部门店 id（前端据此决定是否展示"核销"入口）。
     *
     * 【注意】前端只把它当展示开关，真正的拦截在交易域核销入口，不能因为这里是空集合就放行。
     *
     * @param userId 用户 id；为空返回空集合
     */
    List<Long> queryStaffShopIds(Long userId);
}
