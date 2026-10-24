package com.llmall.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.content.domain.po.Follow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface FollowMapper extends BaseMapper<Follow> {

    /**
     * 共同关注（SQL 兜底版本）：仅在 Redis 集合不可信（被清空后基数对不上 / 关注数超过缓存重建上限）时调用。
     *
     * 两侧各命中 uk_follow(user_id, follow_user_id) 前缀做索引扫描，再按 follow_user_id 求交，
     * 内存占用只有结果集大小。
     */
    @Select("SELECT f1.follow_user_id FROM `follow` f1 "
            + "INNER JOIN `follow` f2 ON f1.follow_user_id = f2.follow_user_id "
            + "WHERE f1.user_id = #{userId} AND f2.user_id = #{otherUserId}")
    List<Long> selectCommonFollowIds(@Param("userId") Long userId, @Param("otherUserId") Long otherUserId);

    /**
     * 取"我关注的人"的一页，供读路径按批补拉。
     *
     * 【为什么不用 Redis 关注集合】follow:user:* 是缓存而非权威数据，要确认它完整就得与库里的计数比对，
     * 而那次比对本身就是一条查询，与直接查表等价；这里选一条 SQL 直取，读路径因此没有
     * "集合可信度"这一层分支，也不用区分命中与回源两种条数。
     *
     * 【作者游标】按 follow 行 id 倒序（最近关注优先）；afterId 非空时只取更早关注的那一页，
     * 这样关注数超过单页上限也不会永久漏内容——客户端回传 nextAuthorId 即可继续。
     * 【limit 由调用方传上限加一】多取的那一条只用于判断"还有没有下一页"。
     */
    @Select("<script>SELECT `id`, `follow_user_id` FROM `follow` WHERE `user_id` = #{userId} "
            + "<if test='afterId != null'> AND `id` &lt; #{afterId}</if> "
            + "ORDER BY `id` DESC LIMIT #{limit}</script>")
    List<Follow> selectFollowingPage(@Param("userId") Long userId,
                                     @Param("afterId") Long afterId,
                                     @Param("limit") int limit);
}
