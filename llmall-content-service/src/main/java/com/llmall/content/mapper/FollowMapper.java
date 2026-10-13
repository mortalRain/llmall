package com.llmall.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.content.domain.po.Follow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface FollowMapper extends BaseMapper<Follow> {

    /**
     * 共同关注（SQL 兜底版本）。
     *
     * <p>只在 Redis 集合不可信（被清空后基数对不上 / 关注数超过缓存重建上限）时才走这里：
     * 两边各自命中 uk_follow(user_id, follow_user_id) 的前缀做索引扫描，再按 follow_user_id 求交，
     * 内存占用只有结果集大小 —— 用一趟 SQL 换"结果一定正确"，比拿一个残缺的缓存去求交集划算。
     */
    @Select("SELECT f1.follow_user_id FROM `follow` f1 "
            + "INNER JOIN `follow` f2 ON f1.follow_user_id = f2.follow_user_id "
            + "WHERE f1.user_id = #{userId} AND f2.user_id = #{otherUserId}")
    List<Long> selectCommonFollowIds(@Param("userId") Long userId, @Param("otherUserId") Long otherUserId);
}
