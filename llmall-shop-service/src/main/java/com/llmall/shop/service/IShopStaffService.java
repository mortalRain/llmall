package com.llmall.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.shop.domain.po.ShopStaff;
import com.llmall.shop.domain.vo.MyShopStaffVO;
import com.llmall.shop.domain.vo.ShopStaffVO;

import java.util.List;

/**
 * 门店员工服务：核销权限与管理权限的唯一入口。
 *
 * 【两套权限，别混用】
 * 1) 核销权：任意角色（OWNER/MANAGER/STAFF）都可以。判定只回答"是不是本店员工"，
 *    由 {@link #isStaff(Long, Long)} 承担，交易域核销前实时调用内部接口。
 * 2) 管理权：只有 OWNER/MANAGER 可以增删员工、改角色；只有 OWNER 可以授予/撤销 OWNER；
 *    任何角色都不能把门店的最后一个 OWNER 删除或降级（否则门店永久失去管理员）。
 *
 * 【为什么管理方法都要传 operatorId】权限判定必须由服务端用"当前登录人"回答，
 * 不能由前端传一个 isManager=true 的布尔值。operatorId 只能来自网关注入的 UserContext。
 */
public interface IShopStaffService extends IService<ShopStaff> {

    /**
     * 判断用户是否为指定门店的员工（核销权限）。
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
     * 查询用户任职的全部门店及角色（前端据此决定"核销台 / 员工管理"入口）。
     *
     * 【注意】前端只把它当展示开关，真正的拦截在服务端：核销在交易域、管理在门店域。
     *
     * @param userId 用户 id；为空返回空集合
     */
    List<MyShopStaffVO> queryMyStaff(Long userId);

    /**
     * 门店员工列表（OWNER/MANAGER 可查）。
     *
     * @param shopId     门店 id
     * @param operatorId 当前登录人 id
     * @throws com.llmall.common.exception.ForbiddenException 非本店 OWNER/MANAGER
     */
    List<ShopStaffVO> listStaff(Long shopId, Long operatorId);

    /**
     * 新增员工（OWNER/MANAGER 可操作；授予 OWNER 仅 OWNER 可操作）。
     *
     * @throws com.llmall.common.exception.ConflictException 该用户已是本店员工（409）
     * @throws com.llmall.common.exception.BadRequestException role 非法 / userId 为空
     * @throws com.llmall.common.exception.ForbiddenException 无管理权或 MANAGER 试图授予 OWNER
     */
    void addStaff(Long shopId, Long operatorId, Long targetUserId, String role);

    /**
     * 修改员工角色（OWNER/MANAGER 可操作；授予/撤销 OWNER 仅 OWNER 可操作）。
     *
     * 【最后 OWNER 保护】把门店最后一个 OWNER 降级会被拒绝：
     * 否则一次误操作就会让门店没有任何人能再管理员工（且无法自救）。
     *
     * @throws com.llmall.common.exception.ConflictException 降级最后一个 OWNER
     */
    void updateStaffRole(Long shopId, Long operatorId, Long targetUserId, String role);

    /**
     * 删除员工（OWNER/MANAGER 可操作；删除 OWNER 仅 OWNER 可操作）。
     *
     * 【最后 OWNER 保护】门店最后一个 OWNER 不能被删除（包括操作者自己）。
     *
     * @throws com.llmall.common.exception.ConflictException 删除最后一个 OWNER
     */
    void removeStaff(Long shopId, Long operatorId, Long targetUserId);
}
