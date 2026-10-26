package com.llmall.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.shop.domain.po.ShopFavorite;

/**
 * 门店收藏 Mapper（表 shop_favorite）。
 *
 * 查询全部走 MyBatis-Plus 条件构造器：主路径只有"按 user_id 查列表"与
 * "按 user_id + shop_id 判存在"。前者由唯一键 uk_user_shop 的最左列 user_id 兜底，
 * 后者是唯一键上的等值查询，都没有必要自造 XML。
 */
public interface ShopFavoriteMapper extends BaseMapper<ShopFavorite> {
}
