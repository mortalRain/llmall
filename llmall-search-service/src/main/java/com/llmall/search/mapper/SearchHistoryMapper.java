package com.llmall.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.search.domain.po.SearchHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface SearchHistoryMapper extends BaseMapper<SearchHistory> {
    /** Duplicate searches retain their original id/time; other SQL errors remain visible to the caller. */
    @Insert("INSERT INTO search_history(user_id, keyword) VALUES(#{userId}, #{keyword}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIfAbsent(@Param("userId") Long userId, @Param("keyword") String keyword);
}
