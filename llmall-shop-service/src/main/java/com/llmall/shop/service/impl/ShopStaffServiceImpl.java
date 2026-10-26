package com.llmall.shop.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ConflictException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.CollUtils;
import com.llmall.shop.domain.enums.ShopStaffRole;
import com.llmall.shop.domain.po.ShopStaff;
import com.llmall.shop.domain.vo.MyShopStaffVO;
import com.llmall.shop.domain.vo.ShopStaffVO;
import com.llmall.shop.mapper.ShopStaffMapper;
import com.llmall.shop.service.IShopStaffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店员工服务实现。
 *
 * 【性能】isStaff 走唯一键 uk_shop_user 的等值查询，每次核销一次索引命中；
 * 不做缓存是刻意的：员工离职/调店必须"改完即生效"，缓存会让撤销权限延迟到 TTL 过期。
 *
 * 【管理权限的实现口径】每个写方法都先查"操作者在本店的角色"，再查目标行，
 * 顺序不能反：先查目标再判权会把"目标是否存在"泄漏给无权限者。
 * 所有拒绝都抛异常（403/409/400），不做"静默不执行"——静默会让调用方以为成功。
 */
@Slf4j
@Service
public class ShopStaffServiceImpl extends ServiceImpl<ShopStaffMapper, ShopStaff> implements IShopStaffService {

    /**
     * 判定 (shopId, userId) 是否为员工。
     *
     * 【注意】用 count 而不是 selectOne：数据异常（权限表被手工塞了重复行）时 selectOne 会抛
     * TooManyResultsException，统一异常处理器把它翻译成 500，核销直接不可用；count > 0 只关心存在性。
     */
    @Override
    public boolean isStaff(Long shopId, Long userId) {
        if (shopId == null || userId == null) {
            // fail-closed：缺任一侧 id 一律拒绝，且不打 ERROR（调用方漏传属预期内的业务拒绝）
            log.warn("员工身份校验参数缺失，按非员工处理: shopId={}, userId={}", shopId, userId);
            return false;
        }
        Long count = lambdaQuery()
                .eq(ShopStaff::getShopId, shopId)
                .eq(ShopStaff::getUserId, userId)
                .count();
        return count != null && count > 0;
    }

    @Override
    public List<MyShopStaffVO> queryMyStaff(Long userId) {
        if (userId == null) {
            return CollUtils.emptyList();
        }
        // 唯一键保证不会重复，这里仍按 shopId 去重：历史脏数据不应让前端收到重复项
        Map<Long, MyShopStaffVO> byShop = new LinkedHashMap<>();
        List<ShopStaff> rows = lambdaQuery().eq(ShopStaff::getUserId, userId)
                .orderByAsc(ShopStaff::getShopId).list();
        // 数据库已排序；这里再排一次，避免 mock/分库返回乱序时前端门店按钮顺序漂移
        rows.sort(Comparator.comparing(ShopStaff::getShopId));
        for (ShopStaff row : rows) {
            byShop.computeIfAbsent(row.getShopId(), ignored -> {
                MyShopStaffVO vo = new MyShopStaffVO();
                vo.setShopId(row.getShopId());
                vo.setRole(normalizedRole(row.getRole()));
                return vo;
            });
        }
        return new ArrayList<>(byShop.values());
    }

    @Override
    public List<ShopStaffVO> listStaff(Long shopId, Long operatorId) {
        requireManagerOrOwner(shopId, operatorId);
        return lambdaQuery()
                .eq(ShopStaff::getShopId, shopId)
                .orderByAsc(ShopStaff::getId)
                .list()
                .stream()
                // 店主在前、店长其次、员工最后；同角色按加入顺序。前端列表无需再排序
                .sorted((left, right) -> Integer.compare(
                        rolePriority(left.getRole()), rolePriority(right.getRole())))
                .map(row -> {
                    ShopStaffVO vo = new ShopStaffVO();
                    vo.setUserId(row.getUserId());
                    vo.setRole(normalizedRole(row.getRole()));
                    vo.setCreateTime(row.getCreateTime());
                    return vo;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addStaff(Long shopId, Long operatorId, Long targetUserId, String role) {
        ShopStaff operator = requireManagerOrOwner(shopId, operatorId);
        if (targetUserId == null) {
            throw new BadRequestException("userId 不能为空");
        }
        ShopStaffRole targetRole = ShopStaffRole.require(role);
        if (targetRole.isOwner() && !storedRole(operator).isOwner()) {
            // 店长可以管员工，但不能"造一个和自己平级甚至更高"的店主；否则店长可自我提权
            throw new ForbiddenException("只有店主可以授予店主");
        }
        if (findRow(shopId, targetUserId) != null) {
            throw new ConflictException("该用户已是本店员工");
        }

        ShopStaff row = new ShopStaff()
                .setShopId(shopId)
                .setUserId(targetUserId)
                .setRole(targetRole.name());
        try {
            save(row);
        } catch (DuplicateKeyException e) {
            // 并发窗口：两个管理员同时添加同一人，唯一键 uk_shop_user 兜底，翻译成 409 而不是 500
            throw new ConflictException("该用户已是本店员工");
        }
        log.info("新增门店员工: shopId={}, targetUserId={}, role={}, operator={}",
                shopId, targetUserId, targetRole, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStaffRole(Long shopId, Long operatorId, Long targetUserId, String role) {
        ShopStaff operator = requireManagerOrOwner(shopId, operatorId);
        ShopStaff target = requireTarget(shopId, targetUserId);
        ShopStaffRole newRole = ShopStaffRole.require(role);
        ShopStaffRole oldRole = storedRole(target);

        if ((oldRole.isOwner() || newRole.isOwner()) && !storedRole(operator).isOwner()) {
            // 撤销店主与授予店主都只有店主能做；店长连"改动一个店主"都不允许
            throw new ForbiddenException("只有店主可以授予或撤销店主");
        }
        if (oldRole.isOwner() && !newRole.isOwner() && countOwners(shopId) <= 1) {
            throw new ConflictException("不能撤销门店最后一个店主");
        }
        if (oldRole == newRole) {
            return;
        }

        updateById(new ShopStaff().setId(target.getId()).setRole(newRole.name()));
        log.info("修改门店员工角色: shopId={}, targetUserId={}, {} -> {}, operator={}",
                shopId, targetUserId, oldRole, newRole, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeStaff(Long shopId, Long operatorId, Long targetUserId) {
        ShopStaff operator = requireManagerOrOwner(shopId, operatorId);
        ShopStaff target = requireTarget(shopId, targetUserId);
        ShopStaffRole targetRole = storedRole(target);

        if (targetRole.isOwner() && !storedRole(operator).isOwner()) {
            throw new ForbiddenException("只有店主可以撤销店主");
        }
        if (targetRole.isOwner() && countOwners(shopId) <= 1) {
            // 包括"店主删除自己"：门店必须至少保留一个 OWNER，否则再也没人能管理员工
            throw new ConflictException("不能删除门店最后一个店主");
        }

        removeById(target.getId());
        log.info("删除门店员工: shopId={}, targetUserId={}, role={}, operator={}",
                shopId, targetUserId, targetRole, operatorId);
    }

    /**
     * 校验操作者是本店 OWNER/MANAGER，并返回其员工行。
     *
     * 【fail-closed】查不到员工行、角色为空或未知，一律按无管理权拒绝；
     * 绝不允许"角色字段不认识就当店长"。
     */
    private ShopStaff requireManagerOrOwner(Long shopId, Long operatorId) {
        if (shopId == null) {
            throw new BadRequestException("shopId 不能为空");
        }
        if (operatorId == null) {
            throw new UnauthorizedException("未登录");
        }
        ShopStaff operator = findRow(shopId, operatorId);
        if (operator == null || !storedRole(operator).canManageStaff()) {
            throw new ForbiddenException("仅本店店主/店长可管理员工");
        }
        return operator;
    }

    private ShopStaff requireTarget(Long shopId, Long targetUserId) {
        if (targetUserId == null) {
            throw new BadRequestException("userId 不能为空");
        }
        ShopStaff target = findRow(shopId, targetUserId);
        if (target == null) {
            throw new BizIllegalException("该用户不是本店员工");
        }
        return target;
    }

    private ShopStaff findRow(Long shopId, Long userId) {
        return lambdaQuery()
                .eq(ShopStaff::getShopId, shopId)
                .eq(ShopStaff::getUserId, userId)
                .one();
    }

    private long countOwners(Long shopId) {
        Long owners = lambdaQuery()
                .eq(ShopStaff::getShopId, shopId)
                .eq(ShopStaff::getRole, ShopStaffRole.OWNER.name())
                .count();
        return owners == null ? 0L : owners;
    }

    /** 已落库角色：未知值按 STAFF 处理（无管理权，fail-closed），并把脏数据留在日志里 */
    private ShopStaffRole storedRole(ShopStaff row) {
        ShopStaffRole role = ShopStaffRole.from(row.getRole());
        if (role == null) {
            log.warn("shop_staff.role 非法，按 STAFF 处理（fail-closed）: staffId={}, shopId={}, userId={}, role={}",
                    row.getId(), row.getShopId(), row.getUserId(), row.getRole());
            return ShopStaffRole.STAFF;
        }
        return role;
    }

    private String normalizedRole(String role) {
        ShopStaffRole parsed = ShopStaffRole.from(role);
        return parsed == null ? ShopStaffRole.STAFF.name() : parsed.name();
    }

    private int rolePriority(String role) {
        ShopStaffRole parsed = ShopStaffRole.from(role);
        if (parsed == null) {
            return 3;
        }
        switch (parsed) {
            case OWNER:
                return 0;
            case MANAGER:
                return 1;
            default:
                return 2;
        }
    }
}
