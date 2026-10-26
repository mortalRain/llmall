package com.llmall.shop.domain.enums;

import com.llmall.common.exception.BadRequestException;

/**
 * 门店员工角色（shop_staff.role）。
 *
 * 【权限模型】三人三档，核销权是所有角色共有，管理权逐级收紧：
 * <ul>
 *   <li>{@link #OWNER} 店主：核销 + 增删员工 + 改角色 + 授予/撤销 OWNER；</li>
 *   <li>{@link #MANAGER} 店长：核销 + 增删 STAFF/MANAGER；不能授予或撤销 OWNER；</li>
 *   <li>{@link #STAFF} 员工：仅核销。</li>
 * </ul>
 *
 * 【为什么用枚举而不是裸字符串】role 是权限判定输入，写错一个字母（"OWNER " / "owner"）
 * 在旧实现里会被当成"未知角色"静默降级；这里统一大小写并拒绝非法值，
 * 让错误在入口暴露，而不是在判权时变成"这人怎么没权限"。
 *
 * 【注意】数据库 role 列没有 CHECK 约束，历史行可能是小写或空值；
 * 读取存量数据用 {@link #from(String)}（未知值返回 null，调用方按无管理权处理，fail-closed），
 * 只有解析"请求体里的角色"才用 {@link #require(String)}（非法值直接 400）。
 */
public enum ShopStaffRole {

    OWNER,
    MANAGER,
    STAFF;

    /**
     * 解析已落库的角色：忽略大小写与首尾空白，未知/空值返回 null。
     *
     * 【fail-closed】未知角色不能默认成 MANAGER 或 OWNER，否则一行脏数据就会提权。
     */
    public static ShopStaffRole from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        for (ShopStaffRole role : values()) {
            if (role.name().equals(normalized)) {
                return role;
            }
        }
        return null;
    }

    /** 解析请求体里的角色：非法值直接 400，避免"传错角色被静默当成 STAFF" */
    public static ShopStaffRole require(String value) {
        ShopStaffRole role = from(value);
        if (role == null) {
            throw new BadRequestException("role 取值不合法，只能是 OWNER / MANAGER / STAFF");
        }
        return role;
    }

    /** OWNER / MANAGER 才可管理员工（增删、改角色、查看列表） */
    public boolean canManageStaff() {
        return this == OWNER || this == MANAGER;
    }

    public boolean isOwner() {
        return this == OWNER;
    }
}
