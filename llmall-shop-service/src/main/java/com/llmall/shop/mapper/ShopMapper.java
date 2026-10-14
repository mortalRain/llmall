package com.llmall.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.shop.domain.po.Shop;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ShopMapper extends BaseMapper<Shop> {

    /**
     * 评价到达后原子更新门店统计：评价数 +1、按新评价重算平均分、刷新最近评价时间。
     *
     * 【注意】必须"先算 score、再自增 comments"：MySQL 单表 UPDATE 中后面的赋值会看到前面刚赋的新值，
     * 顺序写反会让分母多 1、平均分被系统性算低；一条 SQL 完成"读-算-写"，避免并发评价下丢更新。
     */
    @Update("UPDATE shop SET " +
            "score = ROUND((score * comments + #{score} * 10) / (comments + 1)), " +
            "comments = comments + 1, " +
            "last_review_time = NOW(), " +
            "update_time = NOW() " +
            "WHERE id = #{shopId}")
    int applyReview(@Param("shopId") Long shopId, @Param("score") Integer score);

    /** 订单支付成功后累加门店销量（原子自增，避免读改写丢更新） */
    @Update("UPDATE shop SET sold = sold + #{num}, update_time = NOW() WHERE id = #{shopId}")
    int addSold(@Param("shopId") Long shopId, @Param("num") Integer num);
}
