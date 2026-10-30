package com.llmall.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.content.domain.po.FeedInbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

public interface FeedInboxMapper extends BaseMapper<FeedInbox> {
    /** V2按笔记发布时间取候选，在LIMIT前排除已删除/不可见内容。 */
    @Select("<script>SELECT f.id,f.user_id,f.blog_id,f.author_id,b.create_time FROM feed_inbox f "
            + "JOIN blog b ON b.id=f.blog_id WHERE f.user_id=#{userId} AND (b.status=1 OR b.user_id=#{userId}) "
            + "<if test='ct != null'>AND (b.create_time &lt; #{ct} OR (b.create_time=#{ct} AND b.id &lt; #{ci})) </if>"
            + "ORDER BY b.create_time DESC,b.id DESC LIMIT #{limit}</script>")
    List<FeedInbox> selectFeedPage(@Param("userId") Long userId, @Param("ct") LocalDateTime ct,
            @Param("ci") Long ci, @Param("limit") int limit);
}
