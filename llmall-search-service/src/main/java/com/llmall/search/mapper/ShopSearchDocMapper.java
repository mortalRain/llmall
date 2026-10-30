package com.llmall.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.search.domain.po.ShopSearchDoc;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ShopSearchDocMapper extends BaseMapper<ShopSearchDoc> {
    @Select("SELECT * FROM shop_search_doc WHERE shop_id=#{shopId} FOR UPDATE")
    ShopSearchDoc selectForUpdate(@Param("shopId") Long shopId);

    /** 同店首次创建竞争时只锁住已有行，不覆盖其中的更新数据。 */
    @Insert("INSERT INTO shop_search_doc(shop_id,name,type_id,type_name,area,address,x,y,avg_price,score,comments,sold,hot_score,status,event_time) "
            + "VALUES(#{d.shopId},#{d.name},#{d.typeId},#{d.typeName},#{d.area},#{d.address},#{d.x},#{d.y},#{d.avgPrice},"
            + "COALESCE(#{d.score},0),COALESCE(#{d.comments},0),COALESCE(#{d.sold},0),COALESCE(#{d.hotScore},0),COALESCE(#{d.status},1),#{d.eventTime}) "
            + "ON DUPLICATE KEY UPDATE shop_id=shop_id")
    int insertIfAbsent(@Param("d") ShopSearchDoc doc);
}
