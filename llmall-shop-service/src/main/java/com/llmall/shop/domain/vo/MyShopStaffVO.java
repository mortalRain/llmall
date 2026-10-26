package com.llmall.shop.domain.vo;

import lombok.Data;

/**
 * "我任职的门店"列表项：{@code GET /shops/my/staff}。
 *
 * 【为什么从 List<Long> 改成对象】前端既要据此显示"核销台"入口（任意角色），
 * 又要判断是否显示"员工管理"入口（仅 OWNER/MANAGER）。只返回 shopId 时前端无法区分角色，
 * 只能再发一次请求或把所有员工都当管理员 —— 前者浪费一次往返，后者是权限模型上的错误暗示。
 * 真正的管理权限仍在服务端校验，这里的 role 只用于展示与导航。
 */
@Data
public class MyShopStaffVO {

    private Long shopId;

    /** 当前用户在该门店的角色：OWNER / MANAGER / STAFF */
    private String role;
}
