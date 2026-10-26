package com.llmall.shop.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 门店员工：一行表示"某用户是某门店的员工"，是到店核销权限的唯一事实来源。
 *
 * 【为什么需要这张表】核销是门店侧动作（决定门店结算与用户"券已使用"的终态）。修复前
 * {@code POST /orders/verify} 只看"已登录 + 核销码存在 + 门店一致"，任何登录用户拿到核销码
 * 都能核销别人的订单 —— 权限必须由后端强制，前端隐藏按钮只是体验。
 *
 * 【为什么是 (shop_id, user_id) 的多对多】一个人可能同时在多家门店任职（演示账号 Jack 就是
 * 1 号与 10 号店的员工），user 表上的单值角色字段表达不了这种关系；唯一键
 * {@code uk_shop_user} 同时是幂等闸门。
 *
 * 【注意】user_id 是跨库逻辑外键（llmall-user.user.id），没有物理外键：用户域与门店域是两个库、
 * 两个服务，物理外键会让用户域删号被门店域锁死。判断员工身份只能用它，不能用 username
 * （用户名可改，改了权限就丢）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("shop_staff")
public class ShopStaff implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 门店 id（llmall-shop.shop.id） */
    private Long shopId;

    /** 员工用户 id（llmall-user.user.id） */
    private Long userId;

    /**
     * 角色：STAFF 员工 / MANAGER 店长。
     *
     * 【注意】当前鉴权只回答"是不是本店员工"，STAFF 与 MANAGER 同等放行；保留该字段是为了
     * 将来做"只有店长能核销大额单/退款"这类细分权限，不要在判权时直接把它当等级比较。
     */
    private String role;

    /** 成为该门店员工的时间（审计"核销发生时该员工是否已入职"时用得上） */
    private LocalDateTime createTime;
}
