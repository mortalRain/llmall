package com.llmall.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.CollUtils;
import com.llmall.content.constant.ContentRedisScripts;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.llmall.content.constant.ContentRedisConstants.*;

/**
 * 关注与 Feed 流：DB 是唯一权威数据源（uk_follow 保证不重复关注），Redis 集合与计数只是缓存。
 *
 * 【注意】计数缓存未命中必须回源 DB，不得把“Redis 里没有”当成 0；Redis 写一律放在事务提交后
 * （{@link TxAfterCommit}）。缓存是否可信以 SCARD == DB 计数 判定，“key 存在”不等于集合完整，
 * 一加一减导致基数恰好相等的极端情形只能靠 TTL 兜底。
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
            // 已关注：靠唯一索引 uk_follow 天然幂等，吞掉异常继续同步缓存（SADD 幂等，
            // 计数缓存走 delete + 回源 DB，不存在“重复关注把粉丝数加两次”的丢更新问题）
            log.info("重复关注，忽略: {} -> {}", userId, followUserId);
        }
        // Redis 写放在提交之后：关注的事务若回滚，Redis 里不能留下“DB 没有的粉丝”
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
        // 集合求交集 O(min(n,m))，比 SQL 两次 JOIN 快；前提是两边集合都完整，所以先过可信度校验
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
        // LinkedHashSet 保证返回顺序稳定
        return ids.stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 粉丝数：缓存优先，未命中回源 DB —— 不得用集合 SCARD 代替，Redis 被清空后它会静默偏小 */
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
                // 缓存值非法（被手工改过/串了 key）：按未命中处理并回源覆盖，不把脏值当数据返回
                log.warn("粉丝数缓存值非法，按未命中处理: key={}, value={}", key, cached);
            }
        }
        long count = countFollowers(userId);
        // 0 也缓存：空值缓存，挡住“拿不存在的 userId 反复打 DB”的穿透
        redisTemplate.opsForValue().set(key, String.valueOf(count), FANS_COUNT_TTL);
        log.debug("粉丝数缓存未命中，回源 DB: userId={}, count={}", userId, count);
        return count;
    }

    @Override
    public void pushToFollowers(Long authorId, Long blogId) {
        // 阈值必须以 DB 计数为准：Redis 计数可能刚好过期/被清，用它判断会把大 V 当小号做全量写扩散
        long followers = countFollowers(authorId);
        if (followers == 0L) {
            return;
        }
        if (followers > FEED_PUSH_THRESHOLD) {
            // 写扩散成本与粉丝数成正比（大 V 一次发布要写几十万行），改为粉丝读 Feed 时拉取合并
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
        // 滚动分页（游标）：用 id < lastId 而不是 OFFSET，避免深分页性能塌陷与翻页重复/漏数据
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

    /** DB 权威计数：粉丝数，走 idx_followed(follow_user_id) 索引 */
    private long countFollowers(Long userId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, userId));
        return count == null ? 0L : count;
    }

    /**
     * 保证 FOLLOW_KEY+userId 的集合能代表该用户完整的关注关系；不适合/不需要缓存时返回 false。
     * 【注意】只被非事务的读路径调用，因此允许直接回填缓存。
     */
    private boolean ensureFollowSet(Long userId) {
        String key = FOLLOW_KEY + userId;
        long count = countFollowing(userId);
        if (count == 0L) {
            // 关注数为 0：Redis 里的集合（若有）必然是脏的，删掉；空集合即完整视图
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
        if (CollUtils.isEmpty(rows)) {
            redisTemplate.delete(key);
            return true;
        }
        String[] members = rows.stream().map(f -> String.valueOf(f.getFollowUserId())).toArray(String[]::new);
        // DEL + 逐条 SADD + EXPIRE 合成一次 Lua 往返：中途失败会留下“部分视图”集合，
        // 而调用方（共同关注）会把它当完整数据用 —— 得到一个偏少但不报错的结果
        String[] args = new String[members.length + 1];
        args[0] = String.valueOf(FOLLOW_SET_TTL.toMillis());
        System.arraycopy(members, 0, args, 1, members.length);
        redisTemplate.execute(ContentRedisScripts.SET_REBUILD_WITH_TTL,
                Collections.singletonList(key), (Object[]) args);
        log.info("关注集合缓存重建: userId={}, size={}", userId, members.length);
        return true;
    }

    /** DB 权威计数：关注了多少人，走 uk_follow 的 user_id 前缀 */
    private long countFollowing(Long userId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId));
        return count == null ? 0L : count;
    }

    /**
     * 取“关注我的人”的 id 列表供写扩散（调用方已保证总数不超过 FEED_PUSH_THRESHOLD）。
     * 【注意】此处不回填缓存：本方法在 publishBlog 的事务内被调用，而“事务内不写 Redis”是本模块硬约束。
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
     * SADD + 续期 TTL 合并为同一次 Redis 往返（每次写都 expire，即滑动过期）。
     * 【注意】写成员与设 TTL 不可分割：两条独立命令时第二条失败会留下永久无 TTL 的 key，
     * 而这两个集合的 key 是无界的，Redis noeviction 下既不过期也不淘汰。
     */
    private void addToSet(String key, Long member) {
        runSetScript(ContentRedisScripts.SET_ADD_WITH_TTL, key, member);
    }

    /** SREM + 续期 TTL，同样合并成一次往返（集合被删空后 expire 是空操作，不会把 key 复活） */
    private void removeFromSet(String key, Long member) {
        runSetScript(ContentRedisScripts.SET_REMOVE_WITH_TTL, key, member);
    }

    /** 执行“写成员 + 续期”脚本；失败只记日志 —— 缓存写失败不该把关注/取关主链路带崩 */
    private void runSetScript(DefaultRedisScript<Long> script, String key, Long member) {
        try {
            redisTemplate.execute(script, Collections.singletonList(key),
                    String.valueOf(member), String.valueOf(FOLLOW_SET_TTL.toMillis()));
        } catch (Exception e) {
            log.error("写入关注集合缓存失败（DB 已提交，缓存下次读回源重建）: key={}, member={}", key, member, e);
        }
    }

    /** 让粉丝数缓存失效（下次读回源 DB）；用 delete 而非读改写 ±1：并发关注同一人会丢更新 */
    private void evictFansCount(Long userId) {
        redisTemplate.delete(FANS_COUNT_KEY + userId);
    }
}
