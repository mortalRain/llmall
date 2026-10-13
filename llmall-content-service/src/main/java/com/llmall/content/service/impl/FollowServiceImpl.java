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
import com.llmall.content.support.TxAfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.llmall.content.constant.ContentRedisConstants.*;

/**
 * 关注与 Feed 流实现。
 *
 * <h3>数据所有权（本类最重要的一条约定）</h3>
 * DB 是**唯一权威数据源**（uk_follow(user_id, follow_user_id) 保证不重复关注），
 * Redis 里的集合与计数**全部只是缓存**，因此：
 * <ul>
 *   <li>任何计数在缓存未命中时必须回源 DB —— 不允许"Redis 里没有就当成 0"（审计 B5 的原缺陷）；</li>
 *   <li>缓存写入一律放在事务提交之后（{@link TxAfterCommit}），避免"事务回滚了 Redis 却改了"；</li>
 *   <li>集合类 key 全部带 TTL（{@link ContentRedisConstants#FOLLOW_SET_TTL}），防止僵尸 key 无限增长。</li>
 * </ul>
 *
 * <h3>缓存可信度判定</h3>
 * "key 存在"≠"缓存完整"：Redis 被清空（重启/被清/过期）之后，用户只要再关注一个人，
 * 集合 key 就又存在了，但里面只有这一条。实测过这个坑：DB 里 3 个粉丝，接口只报 1。
 * 所以这里统一用 <b>SCARD == DB 计数</b> 来判断缓存是否可信，对不上就回源重建。
 * （残余风险：一加一减导致基数恰好相等的情形理论上会让脏缓存蒙混过关，
 * 由 TTL + 每次写操作的滑动续期兜底。真正零风险只能靠全量对账任务，成本不成比例。）
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
            // 已关注：靠唯一索引 uk_follow 天然幂等，吞掉异常继续同步缓存。
            // SADD 本身幂等，所以"重复关注把粉丝数加两次"在这里是不可能发生的；
            // 计数缓存走的是 delete + 回源 DB 的路线，更没有"读出来 +1"的丢更新问题。
            log.info("重复关注，忽略: {} -> {}", userId, followUserId);
        }
        // Redis 写移到提交之后：关注的事务如果回滚，Redis 里不能留下"DB 没有的粉丝"
        TxAfterCommit.run("follow " + userId + "->" + followUserId, () -> {
            addToSet(FOLLOW_KEY + userId, followUserId);
            addToSet(FOLLOWER_KEY + followUserId, userId);
            evictFansCount(followUserId);
        });
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
        // 取关同理：先落库，SREM / 计数失效都等提交后再做（SREM 也是幂等的）
        TxAfterCommit.run("unfollow " + userId + "->" + followUserId, () -> {
            removeFromSet(FOLLOW_KEY + userId, followUserId);
            removeFromSet(FOLLOWER_KEY + followUserId, userId);
            evictFansCount(followUserId);
        });
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
        // 集合求交集，O(min(n,m)) —— 用 SQL 实现共同关注需要两次 JOIN，数据量大时明显更慢。
        // 前提是两边的缓存集合都**完整**，所以先过一遍可信度校验（见类注释）。
        if (ensureFollowSet(userId) && ensureFollowSet(otherUserId)) {
            Set<String> result = redisTemplate.opsForSet()
                    .intersect(FOLLOW_KEY + userId, FOLLOW_KEY + otherUserId);
            return result == null ? CollUtils.emptySet() : result;
        }
        // 关注数超过重建上限，不适合整体装载：退化成一条 SQL 求交，结果同样是权威的
        List<Long> ids = baseMapper.selectCommonFollowIds(userId, otherUserId);
        if (CollUtils.isEmpty(ids)) {
            return CollUtils.emptySet();
        }
        // 用 LinkedHashSet 保证返回顺序稳定（对外仍是 Set<String>，契约不变）
        return ids.stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 粉丝数：**缓存优先，未命中回源 DB**。
     *
     * <p>这是审计 B5 的核心修复点。原实现是 {@code SCARD follow:follower:{userId}}，
     * 等于把"Redis 里有没有这个集合"当成了"这个人有没有粉丝"：Redis 一重启/一被清，
     * 粉丝数就静默归零（DB 里数据完好），共同关注也跟着空掉。
     */
    @Override
    public long followerCount(Long userId) {
        if (userId == null) {
            return 0L;
        }
        String key = FANS_COUNT_KEY + userId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            try {
                return Long.parseLong(cached);
            } catch (NumberFormatException e) {
                // 缓存值不是数字（被手工改过/串了 key）：按未命中处理，回源覆盖，别把脏值当数据返回
                log.warn("粉丝数缓存值非法，按未命中处理: key={}, value={}", key, cached);
            }
        }
        long count = countFollowers(userId);
        // 0 也缓存：这是刻意的"空值缓存"，挡住"拿不存在的 userId 反复打 DB"的穿透
        redisTemplate.opsForValue().set(key, String.valueOf(count), FANS_COUNT_TTL);
        log.debug("粉丝数缓存未命中，回源 DB: userId={}, count={}", userId, count);
        return count;
    }

    @Override
    public void pushToFollowers(Long authorId, Long blogId) {
        // 阈值判断以 DB 计数为准：Redis 里的计数可能刚好过期/被清，
        // 用它来判断会漏推 —— 几十万粉丝的大 V 会被当成小号做全量写扩散
        long followers = countFollowers(authorId);
        if (followers == 0L) {
            return;
        }
        if (followers > FEED_PUSH_THRESHOLD) {
            // 写扩散的成本与粉丝数成正比：大 V 一次发布要写几十万行，必须放弃写扩散，
            // 改为粉丝读 Feed 时拉取该作者的最新内容再合并（推拉结合）
            log.info("作者 {} 的粉丝数 {} 超过写扩散阈值 {}，跳过推送",
                    authorId, followers, FEED_PUSH_THRESHOLD);
            return;
        }
        int pushed = 0;
        for (String follower : loadFollowerIds(authorId, followers)) {
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

    // ==================== 私有方法 ====================

    /** DB 权威计数：粉丝数。走 idx_followed(follow_user_id) 索引 */
    private long countFollowers(Long userId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, userId));
        return count == null ? 0L : count;
    }

    /**
     * 保证 FOLLOW_KEY+userId 里的集合能代表该用户**完整**的关注关系；不适合/不需要缓存时返回 false。
     * 这个方法只被非事务的读路径调用，所以允许直接回填缓存。
     */
    private boolean ensureFollowSet(Long userId) {
        String key = FOLLOW_KEY + userId;
        long count = countFollowing(userId);
        if (count == 0L) {
            // 一个都没关注：Redis 里的集合（如果有）必然是脏的，删掉；返回 true 表示"空集合已经是完整视图"
            redisTemplate.delete(key);
            return true;
        }
        if (count > CACHE_SET_MAX) {
            log.debug("用户 {} 关注数 {} 超过集合重建上限 {}，放弃缓存改走 SQL", userId, count, CACHE_SET_MAX);
            return false;
        }
        Long cached = redisTemplate.opsForSet().size(key);
        if (cached != null && cached == count) {
            return true;  // 缓存命中且基数与 DB 一致 → 可信
        }
        // 未命中或基数对不上（Redis 被清空后只被单条关注补回来）→ 回源 DB 重建
        List<Follow> rows = lambdaQuery().eq(Follow::getUserId, userId).list();
        redisTemplate.delete(key);
        if (CollUtils.isEmpty(rows)) {
            return true;
        }
        String[] members = rows.stream().map(f -> String.valueOf(f.getFollowUserId())).toArray(String[]::new);
        redisTemplate.opsForSet().add(key, members);
        redisTemplate.expire(key, FOLLOW_SET_TTL);
        log.info("关注集合缓存重建: userId={}, size={}", userId, members.length);
        return true;
    }

    /** DB 权威计数：关注了多少人（走 uk_follow 的 user_id 前缀） */
    private long countFollowing(Long userId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId));
        return count == null ? 0L : count;
    }

    /**
     * 取"关注我的人"的 id 列表，供写扩散使用（调用方已保证总数不超过 FEED_PUSH_THRESHOLD）。
     *
     * <p>注意：这里**不回填缓存**。本方法在 publishBlog 的事务里被调用，而"事务内不写 Redis"
     * 是本模块的硬约束（缓存回填属于修复动作，交给 follow/unfollow 的 afterCommit 与读路径）。
     */
    private List<String> loadFollowerIds(Long authorId, long expected) {
        String key = FOLLOWER_KEY + authorId;
        Long cached = redisTemplate.opsForSet().size(key);
        if (cached != null && cached == expected) {
            Set<String> members = redisTemplate.opsForSet().members(key);
            if (CollUtils.isNotEmpty(members)) {
                return new ArrayList<>(members);
            }
        }
        return lambdaQuery().eq(Follow::getFollowUserId, authorId)
                .last("LIMIT " + FEED_PUSH_THRESHOLD)
                .list().stream()
                .map(f -> String.valueOf(f.getUserId()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * SADD + 续期 TTL。
     * 每次写都 expire 是刻意的"滑动过期"：还在用的关系一直有缓存，僵尸关系到期自然被回收。
     */
    private void addToSet(String key, Long member) {
        redisTemplate.opsForSet().add(key, String.valueOf(member));
        redisTemplate.expire(key, FOLLOW_SET_TTL);
    }

    /** SREM + 续期 TTL（集合被删空后 expire 是空操作，不会把 key 复活） */
    private void removeFromSet(String key, Long member) {
        redisTemplate.opsForSet().remove(key, String.valueOf(member));
        redisTemplate.expire(key, FOLLOW_SET_TTL);
    }

    /**
     * 让粉丝数缓存失效，下次读回源 DB。
     * 刻意用 delete 而不是"读出来 +1/-1"：并发关注同一个人时，读改写会丢更新（和 SQL 里
     * `liked = liked + 1` 代替"查出来再写回"是同一个道理）。
     */
    private void evictFansCount(Long userId) {
        redisTemplate.delete(FANS_COUNT_KEY + userId);
    }
}
