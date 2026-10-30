package com.llmall.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.shop.domain.po.ShopStaff;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 门店员工 Mapper（表 shop_staff）。
 *
 * 员工写操作先锁门店主键行，再判权和修改；锁由同一数据库事务持有。
 */
public interface ShopStaffMapper extends BaseMapper<ShopStaff> {
    @Select("SELECT id FROM shop WHERE id = #{shopId} FOR UPDATE")
    Long lockShopForStaffChange(@Param("shopId") Long shopId);
}
