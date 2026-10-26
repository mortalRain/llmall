package com.llmall.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.shop.domain.po.ShopStaff;

/**
 * 门店员工 Mapper（表 shop_staff）。
 *
 * 判权查询全部走 MyBatis-Plus 的条件构造器（见 {@code ShopStaffServiceImpl}），
 * 不额外写 XML：条件只有 shop_id + user_id，走唯一键 uk_shop_user，没必要自造 SQL。
 */
public interface ShopStaffMapper extends BaseMapper<ShopStaff> {
}
