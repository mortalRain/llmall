package com.llmall.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.api.dto.ShopDTO;
import com.llmall.shop.domain.po.ShopFavorite;

import java.util.List;

/**
 * 门店收藏服务契约。
 *
 * 【幂等口径】收藏与取消收藏都是幂等操作：重复收藏返回成功（不报 409），
 * 取消一个本来就不存在的收藏也返回成功。前端按钮可能被连点/重试，把"已经是目标状态"
 * 当成错误只会制造无意义的报错。
 */
public interface IShopFavoriteService extends IService<ShopFavorite> {

    /** 收藏门店；已收藏时直接返回（幂等）。门店不存在返回 400。 */
    void addFavorite(Long shopId, Long userId);

    /** 取消收藏；未收藏时也视为成功（幂等）。 */
    void removeFavorite(Long shopId, Long userId);

    /** 当前用户是否已收藏该门店。 */
    boolean isFavorite(Long shopId, Long userId);

    /**
     * 我的收藏（按收藏时间倒序）。
     *
     * 返回 {@link ShopDTO} 而不是关系行：前端列表要展示门店名称/图片/评分，
     * 只给 shopId 会让前端逐条再查一次。门店已被物理删除的行直接跳过，不返回半截数据。
     */
    List<ShopDTO> listMyFavorites(Long userId);
}
