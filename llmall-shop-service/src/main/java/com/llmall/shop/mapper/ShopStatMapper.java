package com.llmall.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.shop.domain.po.ShopStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 门店访问统计 Mapper（表 shop_stat）。
 */
public interface ShopStatMapper extends BaseMapper<ShopStat> {

    /**
     * 落库一家门店某天的访问统计（原子 upsert）。
     *
     * 【注意】靠唯一键 {@code uk_shop_date(shop_id, stat_date)} 保证幂等：落库任务重跑、或多实例同时触发，
     * 都只会覆盖成同一份值而不会插出重复行。SQL 不使用 MySQL 8.0.20 起废弃的 {@code VALUES(col)} 语法，
     * 全部走参数占位，5.7 与 8.x 均可用。
     *
     * @return 1=新增；2=覆盖已有行
     */
    @Insert("INSERT INTO shop_stat (shop_id, stat_date, pv, uv, create_time, update_time) " +
            "VALUES (#{shopId}, #{statDate}, #{pv}, #{uv}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE pv = #{pv}, uv = #{uv}, update_time = NOW()")
    int upsert(@Param("shopId") Long shopId, @Param("statDate") LocalDate statDate,
               @Param("pv") long pv, @Param("uv") long uv);
}
