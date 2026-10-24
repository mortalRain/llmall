package com.llmall.content.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.content.domain.po.Follow;
import com.llmall.content.domain.vo.FeedItemVO;
import com.llmall.content.domain.vo.FeedPageVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface IFollowService extends IService<Follow> {

    /** 关注（幂等） */
    void follow(Long userId, Long followUserId);

    /** 取消关注（幂等） */
    void unfollow(Long userId, Long followUserId);

    boolean isFollowed(Long userId, Long followUserId);

    /** 共同关注（Redis 集合求交集） */
    Set<String> commonFollows(Long userId, Long otherUserId);

    /** 粉丝数（缓存优先，未命中回源 DB 计数；不得用关注集合的基数代替，Redis 被清空后会静默偏小） */
    long followerCount(Long userId);

    /** 发布笔记后推送到粉丝收件箱（粉丝数超过阈值不做写扩散） */
    void pushToFollowers(Long authorId, Long blogId);

    /** 滚动分页查询 Feed 的收件箱记录 id 列表（推模式），lastId 为上一页最后一条收件箱记录 id，首页传 null */
    List<Long> queryFeed(Long userId, Long lastId, Integer size);

    /**
     * 滚动分页查询 Feed 的完整内容：收件箱记录 + 笔记详情（一条 IN 批量取回后按收件箱顺序合并）。
     *
     * 【入参约束】lastId 是上一页最后一条的收件箱记录 id（不是 blogId）；size 超过 50 按 50 处理。
     * 【返回】按收件箱倒序；笔记已被删除或对当前用户不可见的条目会被跳过（因此返回条数可能少于 size）。
     */
    List<FeedItemVO> queryFeedItems(Long userId, Long lastId, Integer size);

    /**
     * 推拉结合 Feed：收件箱（推）与大V 作者近若干条（拉）两路归并后分页。
     *
     * 【与 queryFeedItems 的关系】上面那个方法保持纯推语义不变，作为旧口径继续可用；
     * 本方法新增补拉，因此需要能同时表达两路进度位的复合游标，不复用单一的 lastId。
     *
     * 【入参约束】lastInboxId 为收件箱侧已消费到的记录 id；lastCt 与 lastBlogId 为合并时间轴上的位置，
     * 两者必须成对传入；首页三个参数全传 null。size 超过 50 按 50 处理。
     * afterAuthorId 为补拉作者游标：首页传 null，后续页回传上一页的 nextAuthorId。
     *
     * 【返回】按发布时间倒序的一页内容与下一页游标（收件箱、时间轴、作者三路）；
     * 对当前用户不可见的条目仍会被跳过。
     */
    FeedPageVO queryFeedItemsV2(Long userId, Long lastInboxId, LocalDateTime lastCt, Long lastBlogId,
                                Long afterAuthorId, Integer size);
}
