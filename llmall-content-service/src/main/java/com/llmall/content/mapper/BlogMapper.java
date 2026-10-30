package com.llmall.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.content.domain.po.Blog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

public interface BlogMapper extends BaseMapper<Blog> {
    /** 所有关注的大V共用发布时间游标；LIMIT只限制返回行，不代表扫描成本恒定。 */
    @Select("<script>SELECT b.id,b.user_id,b.create_time FROM blog b "
            + "JOIN (SELECT f.follow_user_id AS aid FROM `follow` f "
            + "WHERE f.follow_user_id IN (SELECT mine.follow_user_id FROM `follow` mine WHERE mine.user_id=#{userId}) "
            + "GROUP BY f.follow_user_id HAVING COUNT(*) &gt; #{threshold}) bigv ON b.user_id=bigv.aid "
            + "WHERE b.status=1 "
            + "<if test='ct != null'>AND (b.create_time &lt; #{ct} OR (b.create_time=#{ct} AND b.id &lt; #{ci})) </if>"
            + "ORDER BY b.create_time DESC,b.id DESC LIMIT #{limit}</script>")
    List<Blog> selectBigvFeedPage(@Param("userId") Long userId, @Param("threshold") int threshold,
            @Param("ct") LocalDateTime ct, @Param("ci") Long ci, @Param("limit") int limit);
}
