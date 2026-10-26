package com.llmall.shop.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.common.utils.CollUtils;
import com.llmall.shop.domain.po.ShopStaff;
import com.llmall.shop.mapper.ShopStaffMapper;
import com.llmall.shop.service.IShopStaffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 门店员工服务实现。
 *
 * 【性能】isStaff 走唯一键 uk_shop_user 的等值查询，每次核销一次索引命中；
 * 不做缓存是刻意的：员工离职/调店必须"改完即生效"，缓存会让撤销权限延迟到 TTL 过期。
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
    public List<Long> queryStaffShopIds(Long userId) {
        if (userId == null) {
            return CollUtils.emptyList();
        }
        return lambdaQuery()
                .eq(ShopStaff::getUserId, userId)
                .list()
                .stream()
                .map(ShopStaff::getShopId)
                // 唯一键保证不会重复，这里仍做一次去重：历史脏数据不应让前端收到重复的 shopId
                .distinct()
                .collect(Collectors.toList());
    }
}
