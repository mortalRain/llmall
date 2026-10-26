package com.llmall.shop.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.CollUtils;
import com.llmall.shop.domain.po.ShopFavorite;
import com.llmall.shop.mapper.ShopFavoriteMapper;
import com.llmall.shop.service.IShopFavoriteService;
import com.llmall.shop.service.IShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 门店收藏服务实现。
 *
 * 【幂等实现】先查后插 + 唯一键 uk_user_shop 兜底：
 * - 正常路径查不到才插入；
 * - 并发连点下两个请求可能都查到 0，第二个插入撞唯一键，捕获 DuplicateKeyException 后按成功返回；
 * - 不用 {@code INSERT IGNORE} 的原因是本项目 Mapper 统一走 MyBatis-Plus 的 save，
 *   捕获唯一键冲突与 {@code ShopStaffServiceImpl} 的并发处理保持同一套写法。
 *
 * 【为什么先校验门店存在】收藏一个不存在的门店只会在"我的收藏"里留下一条永远渲染不出来的孤儿行；
 * 门店详情页本身也是先查门店再渲染，不存在时前端根本到不了收藏按钮，这里的校验是防直连接口的兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopFavoriteServiceImpl extends ServiceImpl<ShopFavoriteMapper, ShopFavorite>
        implements IShopFavoriteService {

    private final IShopService shopService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addFavorite(Long shopId, Long userId) {
        requireLogin(userId);
        if (shopId == null) {
            throw new BizIllegalException("门店 id 不能为空");
        }
        if (shopService.getById(shopId) == null) {
            throw new BizIllegalException("门店不存在: " + shopId);
        }
        if (exists(shopId, userId)) {
            // 幂等：已经是收藏状态，不重复插入、也不报错
            return;
        }
        try {
            save(new ShopFavorite().setUserId(userId).setShopId(shopId));
        } catch (DuplicateKeyException e) {
            // 并发窗口：两个请求同时通过上面的 exists 检查，唯一键 uk_user_shop 挡住第二次
            log.info("重复收藏，忽略: userId={}, shopId={}", userId, shopId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFavorite(Long shopId, Long userId) {
        requireLogin(userId);
        if (shopId == null) {
            throw new BizIllegalException("门店 id 不能为空");
        }
        // 删除 0 行也返回成功：取消一个本来就不存在的收藏，目标状态已经达成
        remove(lambdaQuery()
                .eq(ShopFavorite::getUserId, userId)
                .eq(ShopFavorite::getShopId, shopId)
                .getWrapper());
    }

    @Override
    public boolean isFavorite(Long shopId, Long userId) {
        if (shopId == null || userId == null) {
            // fail-closed：未登录/缺参数一律按"未收藏"处理，不查库
            return false;
        }
        return exists(shopId, userId);
    }

    @Override
    public List<ShopDTO> listMyFavorites(Long userId) {
        if (userId == null) {
            return CollUtils.emptyList();
        }
        List<ShopFavorite> rows = lambdaQuery()
                .eq(ShopFavorite::getUserId, userId)
                .orderByDesc(ShopFavorite::getCreateTime)
                .orderByDesc(ShopFavorite::getId)
                .list();
        if (CollUtils.isEmpty(rows)) {
            return CollUtils.emptyList();
        }

        List<Long> shopIds = rows.stream()
                .map(ShopFavorite::getShopId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (shopIds.isEmpty()) {
            return CollUtils.emptyList();
        }

        // queryByIds 不保证顺序，这里以收藏时间倒序为准重建列表；已删除门店自然被跳过
        Map<Long, ShopDTO> byId = new HashMap<>();
        for (ShopDTO shop : shopService.queryByIds(shopIds)) {
            if (shop != null && shop.getId() != null) {
                byId.put(shop.getId(), shop);
            }
        }
        List<ShopDTO> result = new ArrayList<>(byId.size());
        for (ShopFavorite row : rows) {
            ShopDTO shop = byId.get(row.getShopId());
            if (shop != null) {
                result.add(shop);
            }
        }
        return result.isEmpty() ? CollUtils.emptyList() : result;
    }

    /** 唯一键 uk_user_shop 上的等值存在性查询；用 count 避免脏数据重复行时 selectOne 抛异常 */
    private boolean exists(Long shopId, Long userId) {
        Long count = lambdaQuery()
                .eq(ShopFavorite::getUserId, userId)
                .eq(ShopFavorite::getShopId, shopId)
                .count();
        return count != null && count > 0;
    }

    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
    }
}
