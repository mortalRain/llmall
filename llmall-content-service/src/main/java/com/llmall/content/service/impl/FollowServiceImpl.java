package com.llmall.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.CollUtils;
import com.llmall.content.domain.po.FeedInbox;
import com.llmall.content.domain.po.Follow;
import com.llmall.content.mapper.FeedInboxMapper;
import com.llmall.content.mapper.FollowMapper;
import com.llmall.content.service.IFollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.llmall.content.constant.ContentRedisConstants.*;

/**
 * 关注与 Feed 流实现。
 *
 * 关注关系同时写 DB 与 Redis 集合：
 *  - DB 是权威数据（唯一索引防重复关注），用于对账与重建缓存；
 *  - Redis 集合（我关注的人 / 关注我的人）用于 O(1) 判断与"共同关注"求交集，
 *    比每次 JOIN 两张表高效得多。
 * Feed 采用**推拉结合**：普通作者写扩散到粉丝收件箱，大 V 跳过、由粉丝读时拉取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final FeedInboxMapper feedInboxMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void follow(Long userId, Long followUserId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        if (followUserId == null || userId.equals(followUserId)) {
            throw new BizIllegalException("不能关注自己");
        }
        try {
            save(new Follow().setUserId(userId).setFollowUserId(followUserId));
        } catch (DuplicateKeyException e) {
            // 已关注：幂等返回
            log.info("重复关注，忽略: {} -> {}", userId, followUserId);
        }
        redisTemplate.opsForSet().add(FOLLOW_KEY + userId, String.valueOf(followUserId));
        redisTemplate.opsForSet().add(FOLLOWER_KEY + followUserId, String.valueOf(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unfollow(Long userId, Long followUserId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        lambdaUpdate()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId)
                .remove();
        redisTemplate.opsForSet().remove(FOLLOW_KEY + userId, String.valueOf(followUserId));
        redisTemplate.opsForSet().remove(FOLLOWER_KEY + followUserId, String.valueOf(userId));
    }

    @Override
    public boolean isFollowed(Long userId, Long followUserId) {
        if (userId == null || followUserId == null) {
            return false;
        }
        return lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId)
                .one() != null;
    }

    @Override
    public Set<String> commonFollows(Long userId, Long otherUserId) {
        if (userId == null || otherUserId == null) {
            return CollUtils.emptySet();
        }
        // 集合求交集，O(min(n,m)) —— 用 SQL 实现共同关注需要两次 JOIN，数据量大时明显更慢
        Set<String> result = redisTemplate.opsForSet()
                .intersect(FOLLOW_KEY + userId, FOLLOW_KEY + otherUserId);
        return result == null ? CollUtils.emptySet() : result;
    }

    @Override
    public long followerCount(Long userId) {
        Long size = redisTemplate.opsForSet().size(FOLLOWER_KEY + userId);
        return size == null ? 0L : size;
    }

    @Override
    public void pushToFollowers(Long authorId, Long blogId) {
        Set<String> followers = redisTemplate.opsForSet().members(FOLLOWER_KEY + authorId);
        if (CollUtils.isEmpty(followers)) {
            return;
        }
        if (followers.size() > FEED_PUSH_THRESHOLD) {
            // 写扩散的成本与粉丝数成正比：大 V 一次发布要写几十万行，必须放弃写扩散，
            // 改为粉丝读 Feed 时拉取该作者的最新内容再合并（推拉结合）
            log.info("作者 {} 的粉丝数 {} 超过写扩散阈值 {}，跳过推送",
                    authorId, followers.size(), FEED_PUSH_THRESHOLD);
            return;
        }
        int pushed = 0;
        for (String follower : followers) {
            try {
                feedInboxMapper.insert(new FeedInbox()
                        .setUserId(Long.valueOf(follower))
                        .setBlogId(blogId)
                        .setAuthorId(authorId));
                pushed++;
            } catch (DuplicateKeyException e) {
                // (user_id, blog_id) 唯一索引：重复推送幂等
                log.debug("Feed 重复推送，忽略: user={}, blog={}", follower, blogId);
            }
        }
        log.info("Feed 写扩散完成: author={}, blog={}, 推送 {} 人", authorId, blogId, pushed);
    }

    @Override
    public List<Long> queryFeed(Long userId, Long lastId, Integer size) {
        if (userId == null) {
            return CollUtils.emptyList();
        }
        int limit = (size == null || size <= 0) ? 10 : Math.min(size, 50);
        // 滚动分页（游标）：用 id < lastId 而不是 OFFSET，避免深分页性能塌陷与"翻页重复/漏数据"
        List<FeedInbox> list = feedInboxMapper.selectList(new LambdaQueryWrapper<FeedInbox>()
                .eq(FeedInbox::getUserId, userId)
                .lt(lastId != null, FeedInbox::getId, lastId)
                .orderByDesc(FeedInbox::getId)
                .last("LIMIT " + limit));
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        return list.stream().map(FeedInbox::getBlogId).collect(Collectors.toCollection(ArrayList::new));
    }
}
