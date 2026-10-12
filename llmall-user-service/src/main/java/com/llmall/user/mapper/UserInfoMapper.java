package com.llmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.user.domain.po.UserInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserInfoMapper extends BaseMapper<UserInfo> {

    /**
     * 原子累加积分。
     * 必须是 SQL 自增而不是"查出来 + 加 + 写回"：后者在并发（多渠道同时发积分）下会丢更新。
     */
    @Update("UPDATE user_info SET credits = credits + #{points}, update_time = NOW() WHERE user_id = #{userId}")
    int addCredits(@Param("userId") Long userId, @Param("points") Integer points);
}
