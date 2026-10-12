package com.llmall.content.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.content.domain.po.Follow;

import java.util.List;
import java.util.Set;

public interface IFollowService extends IService<Follow> {

    /** 关注（幂等） */
    void follow(Long userId, Long followUserId);

    /** 取消关注（幂等） */
    void unfollow(Long userId, Long followUserId);

    /** 是否已关注 */
    boolean isFollowed(Long userId, Long followUserId);

    /** 共同关注（Redis 集合求交集） */
    Set<String> commonFollows(Long userId, Long otherUserId);

    /** 粉丝数（Redis 集合基数） */
    long followerCount(Long userId);

    /** 发布笔记后推送到粉丝收件箱（超过阈值不做写扩散） */
    void pushToFollowers(Long authorId, Long blogId);

    /**
     * 滚动分页查询 Feed（推模式：查收件箱）
     *
     * @param lastId 上一页最后一条收件箱记录 id，首页传 null
     */
    List<Long> queryFeed(Long userId, Long lastId, Integer size);
}
