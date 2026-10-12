package com.llmall.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.shop.domain.po.Shop;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ShopMapper extends BaseMapper<Shop> {

    /**
     * 评价到达后原子更新门店统计：评价数 +1、评分按新评价重算平均分、刷新最近评价时间。
     *
     * ⚠️ 赋值顺序很重要：MySQL 的单表 UPDATE 中，后面的赋值会看到**前面刚被赋的新值**。
     * 所以必须"先算 score（此时 comments 还是旧值），再自增 comments"；
     * 若写反了（先 comments+1 再算 score），分母会多 1，平均分被系统性算低。
     * 这里刻意用一条 SQL 完成"读-算-写"，避免"先查出来再算再写回"在并发评价下丢更新。
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
