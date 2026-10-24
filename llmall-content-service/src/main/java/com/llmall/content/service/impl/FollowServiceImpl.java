package com.llmall.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.utils.CollUtils;
import com.llmall.common.utils.UserContext;
import com.llmall.content.constant.ContentRedisScripts;
import com.llmall.content.domain.po.Blog;
import com.llmall.content.domain.po.FeedInbox;
import com.llmall.content.domain.po.Follow;
import com.llmall.content.domain.vo.FeedItemVO;
import com.llmall.content.domain.vo.FeedPageVO;
import com.llmall.content.mapper.BlogMapper;
import com.llmall.content.mapper.FeedInboxMapper;
import com.llmall.content.mapper.FollowMapper;
import com.llmall.content.service.IFollowService;
import com.llmall.common.tx.TxAfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** 笔记状态"已发布"：对外可见的唯一状态（与笔记详情的可见性判据保持一致） */
    private static final int STATUS_PUBLISHED = 1;

    /** 补拉时每个大V 作者最多取几条：取页面上限的一半，既能让单个作者填满半页，也不让每批回表量翻倍 */
    private static final int FEED_PULL_PER_AUTHOR = 20;

    /** 补拉按作者分批的批大小：每批一条 SQL，批大小同时决定 IN 参数长度与批数 */
    private static final int FEED_PULL_AUTHOR_BATCH = 20;

    /** 单页参与补拉的大V 作者数上限：条数上界靠它卡死；超出时在响应里显式标记 pullTruncated */
    private static final int FEED_PULL_AUTHOR_CAP = 200;

    private final FeedInboxMapper feedInboxMapper;
    private final BlogMapper blogMapper;
    private final StringRedisTemplate redisTemplate;
    private final BizMetrics metrics;

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
            // 写扩散成本与粉丝数成正比（大 V 一次发布要写几十万行），超阈值直接跳过推送；
            // 跳过之后收件箱里不会再有这条笔记的行，它只能经读路径补拉（/follows/feed/v2）到达粉丝。
            // 【可观测性】WARN 文案保持不变：该字符串已被台账登记为告警关键字，它描述的是"未写扩散"这个事件，
            // 改文案会打断按关键字告警与统计；内容最终是否可见由读路径补拉决定，不由这条日志决定。
            log.warn("[Feed] 写扩散被阈值跳过，本条笔记不会出现在粉丝 Feed 里: author={}, blogId={}, followers={}, threshold={}",
                    authorId, blogId, followers, FEED_PUSH_THRESHOLD);
            metrics.increment("llmall.feed.fanout.skipped");
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

    /**
     * 读 Feed 的完整内容：先按游标取一页收件箱，再用一条 IN 查询把整页笔记一次取回并按收件箱顺序合并。
     *
     * 【为什么必须批量】逐条查笔记是阅读路径最典型的放大点：一页 10 条就是 10 次往返，
     * 而放大倍数随页大小线性增长。这里的固定口径是"每请求 2 条 SQL"（收件箱 1 条 + 笔记 IN 1 条），
     * 与页大小无关，可直接用 MySQL 的 Com_select 增量 ÷ 请求数 核对。
     *
     * 【可见性】与笔记详情同口径：仅 status=1（已发布）对他人可见，作者本人可看到自己未过审的内容。
     * 不可见或已被删除的条目直接跳过而不是占位返回 —— 返回一个空壳会把"不该给看"变成"看起来没内容"。
     *
     * 【失效条件】若把 IN 查询改回循环内单条查询，本条链路的 SQL 条数会随页大小线性增长；
     * 若去掉可见性判定，被屏蔽的笔记会经 Feed 继续对外传播。
     */
    @Override
    public List<FeedItemVO> queryFeedItems(Long userId, Long lastId, Integer size) {
        if (userId == null) {
            return CollUtils.emptyList();
        }
        int limit = (size == null || size <= 0) ? 10 : Math.min(size, 50);
        List<FeedInbox> inbox = feedInboxMapper.selectList(new LambdaQueryWrapper<FeedInbox>()
                .eq(FeedInbox::getUserId, userId)
                .lt(lastId != null, FeedInbox::getId, lastId)
                .orderByDesc(FeedInbox::getId)
                .last("LIMIT " + limit));
        if (CollUtils.isEmpty(inbox)) {
            return CollUtils.emptyList();
        }
        // 同一篇笔记可能因重复推送出现在多行（唯一索引 uk 只防 (user_id, blog_id) 重复插入，理论上不会），
        // 去重后批量查，保证 IN 参数不会重复膨胀
        List<Long> blogIds = inbox.stream().map(FeedInbox::getBlogId).distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        Map<Long, Blog> blogMap = blogMapper.selectBatchIds(blogIds).stream()
                .collect(Collectors.toMap(Blog::getId, blog -> blog, (left, right) -> left));

        Long currentUserId = UserContext.getUser();
        List<FeedItemVO> items = new ArrayList<>(inbox.size());
        for (FeedInbox row : inbox) {
            Blog blog = blogMap.get(row.getBlogId());
            if (blog == null) {
                // 笔记被物理删除：收件箱里还留着指针，跳过而不是抛异常，否则一条脏数据会让整页读不出来
                log.debug("Feed 项对应的笔记已不存在，跳过: feedId={}, blogId={}", row.getId(), row.getBlogId());
                continue;
            }
            boolean authorSelf = currentUserId != null && currentUserId.equals(blog.getUserId());
            if (!Integer.valueOf(STATUS_PUBLISHED).equals(blog.getStatus()) && !authorSelf) {
                log.debug("Feed 项对应笔记当前不可见，跳过: feedId={}, blogId={}, status={}",
                        row.getId(), row.getBlogId(), blog.getStatus());
                continue;
            }
            items.add(toFeedItem(row, blog));
        }
        return items;
    }

    @Override
    public FeedPageVO queryFeedItemsV2(Long userId, Long lastInboxId, LocalDateTime lastCt, Long lastBlogId, Integer size) {
        if (userId == null) {
            return new FeedPageVO().setItems(CollUtils.emptyList());
        }
        int limit = (size == null || size <= 0) ? 10 : Math.min(size, 50);

        // 【条数上界第一笔】关注列表单独一条 SQL。多取一条只为判断是否超过补拉上限，
        // 溢出时截断并告警：先保证条数上界可计算，再谈补拉多少。
        List<Long> following = baseMapper.selectFollowingIds(userId, FEED_PULL_AUTHOR_CAP + 1);
        boolean pullTruncated = false;
        if (following.size() > FEED_PULL_AUTHOR_CAP) {
            // 不再静默截断：响应里带 pullTruncated，客户端可提示"部分关注内容未展示"。
            // 上限从 60 提到 200 只是把影响面缩小；彻底解决需要作者维度游标（登记在台账 R-03）。
            log.warn("[Feed] 关注作者数 {} 超过补拉上限 {}，本页只覆盖最近关注的一批: userId={}",
                    following.size(), FEED_PULL_AUTHOR_CAP, userId);
            following = new ArrayList<>(following.subList(0, FEED_PULL_AUTHOR_CAP));
            pullTruncated = true;
        }

        // 收件箱侧仍按主键倒序取：feed_inbox 的索引是 (user_id, id DESC)，按发布时间过滤没有索引可用，
        // 因此这里不按时间游标过滤，改为归并时统一时间轴。取 limit 条足够：两路各自降序时，
        // 各自的前 limit 条就足以算出归并后的前 limit 条，多取无益。
        // 该查询的访问路径由 (user_id, id DESC) 保证；数据量很小时优化器可能改用 uk_user_blog
        // 并对该用户自己的收件箱排一次序，两种走法都只读取当前用户的行，不随全表规模增长。
        List<FeedInbox> inbox = feedInboxMapper.selectList(new LambdaQueryWrapper<FeedInbox>()
                .eq(FeedInbox::getUserId, userId)
                .lt(lastInboxId != null, FeedInbox::getId, lastInboxId)
                .orderByDesc(FeedInbox::getId)
                .last("LIMIT " + limit));

        // 【条数上界其余各笔】补拉按作者分批，每批一条 SQL：
        // 条数 = 收件箱 1 + 详情批量 1 + 关注列表 1 + 批数，批数 = 关注作者数除以批大小向上取整。
        List<Blog> pulled = new ArrayList<>();
        for (int i = 0; i < following.size(); i += FEED_PULL_AUTHOR_BATCH) {
            List<Long> batch = following.subList(i, Math.min(i + FEED_PULL_AUTHOR_BATCH, following.size()));
            pulled.addAll(blogMapper.selectBigvRecentBlogs(batch, FEED_PUSH_THRESHOLD, lastCt, lastBlogId,
                    batch.size() * FEED_PULL_PER_AUTHOR));
        }
        // 每作者截断：SQL 只做全局 LIMIT，某个作者在同一时间窗内发得特别密会把其他作者的候选挤掉，
        // 因此在内存里再按作者取前 N 条，保证一页不被单个作者占满。
        Map<Long, Integer> perAuthor = new HashMap<>();
        List<Blog> candidates = new ArrayList<>();
        for (Blog blog : pulled) {
            if (perAuthor.merge(blog.getUserId(), 1, Integer::sum) <= FEED_PULL_PER_AUTHOR) {
                candidates.add(blog);
            }
        }

        // 归并去重：作者昨天粉丝未过阈值时笔记已被推进收件箱，今天过阈值后新笔记只走补拉；
        // 跨阈值那一刻两路数据集相交，同一篇笔记会同时出现在两边，按 blog_id 去重是这条链路的必需品。
        Set<Long> seenBlogIds = new HashSet<>();
        List<FeedCandidate> merged = new ArrayList<>(inbox.size() + candidates.size());
        for (FeedInbox row : inbox) {
            if (seenBlogIds.add(row.getBlogId())) {
                merged.add(new FeedCandidate(row.getBlogId(), row.getCreateTime(), row.getId(), row.getAuthorId()));
            }
        }
        for (Blog blog : candidates) {
            if (seenBlogIds.add(blog.getId())) {
                merged.add(new FeedCandidate(blog.getId(), blog.getCreateTime(), null, blog.getUserId()));
            }
        }
        merged.sort(Comparator
                .comparing(FeedCandidate::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(FeedCandidate::getBlogId, Comparator.reverseOrder()));
        if (merged.size() > limit) {
            merged = merged.subList(0, limit);
        }

        FeedPageVO page = new FeedPageVO().setPullApplied(!candidates.isEmpty())
                .setPullTruncated(pullTruncated)
                .setNextLi(lastInboxId).setNextCt(lastCt).setNextCi(lastBlogId);
        if (merged.isEmpty()) {
            return page.setItems(CollUtils.emptyList());
        }

        List<Long> blogIds = merged.stream().map(FeedCandidate::getBlogId).collect(Collectors.toList());
        Map<Long, Blog> detail = blogMapper.selectBatchIds(blogIds).stream()
                .collect(Collectors.toMap(Blog::getId, blog -> blog, (left, right) -> left));
        Long currentUserId = UserContext.getUser();
        List<FeedItemVO> items = new ArrayList<>(merged.size());
        for (FeedCandidate candidate : merged) {
            Blog blog = detail.get(candidate.getBlogId());
            if (blog == null) {
                // 笔记被物理删除：收件箱里还留着指针，跳过而不是抛异常
                continue;
            }
            boolean authorSelf = currentUserId != null && currentUserId.equals(blog.getUserId());
            if (!Integer.valueOf(STATUS_PUBLISHED).equals(blog.getStatus()) && !authorSelf) {
                continue;
            }
            items.add(toFeedItem(candidate.getFeedId(), candidate.getAuthorId(), blog));
            // 游标只随真正返回的条目前进：被跳过的条目不参与翻页，否则一次跳过就会翻丢一页
            page.setNextCt(candidate.getCreateTime()).setNextCi(candidate.getBlogId());
            if (candidate.getFeedId() != null) {
                page.setNextLi(candidate.getFeedId());
            }
        }
        return page.setItems(items);
    }

    /** 归并中间态：一条笔记在合并时间轴上的位置，以及它来自收件箱时的记录 id 与落库作者 */
    private static final class FeedCandidate {
        private final Long blogId;
        private final LocalDateTime createTime;
        private final Long feedId;
        private final Long authorId;

        private FeedCandidate(Long blogId, LocalDateTime createTime, Long feedId, Long authorId) {
            this.blogId = blogId;
            this.createTime = createTime;
            this.feedId = feedId;
            this.authorId = authorId;
        }

        private Long getBlogId() {
            return blogId;
        }

        private LocalDateTime getCreateTime() {
            return createTime;
        }

        private Long getFeedId() {
            return feedId;
        }

        private Long getAuthorId() {
            return authorId;
        }
    }

    /** 收件箱记录 + 笔记详情 → 对外视图；authorId 取收件箱记录上的值（写扩散时落下的作者） */
    private FeedItemVO toFeedItem(FeedInbox row, Blog blog) {
        return toFeedItem(row.getId(), row.getAuthorId(), blog);
    }

    /** 归并后的单条视图构造：补拉来源没有收件箱记录，feedId 为 null、authorId 取笔记作者 */
    private FeedItemVO toFeedItem(Long feedId, Long authorId, Blog blog) {
        return new FeedItemVO()
                .setFeedId(feedId)
                .setBlogId(blog.getId())
                .setAuthorId(authorId != null ? authorId : blog.getUserId())
                .setShopId(blog.getShopId())
                .setTitle(blog.getTitle())
                .setImages(blog.getImages())
                .setContent(blog.getContent())
                .setScore(blog.getScore())
                .setLiked(blog.getLiked())
                .setComments(blog.getComments())
                .setStatus(blog.getStatus())
                .setCreateTime(blog.getCreateTime());
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
