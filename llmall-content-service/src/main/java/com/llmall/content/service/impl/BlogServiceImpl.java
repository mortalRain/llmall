package com.llmall.content.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.client.ShopClient;
import com.llmall.api.dto.ShopDTO;
import com.llmall.api.event.ReviewCreatedEvent;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.domain.PageQuery;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.CollUtils;
import com.llmall.common.utils.UserContext;
import com.llmall.content.constant.ContentRedisScripts;
import com.llmall.content.domain.dto.BlogFormDTO;
import com.llmall.content.domain.po.Blog;
import com.llmall.content.domain.po.BlogComments;
import com.llmall.content.domain.po.BlogLike;
import com.llmall.content.mapper.BlogCommentsMapper;
import com.llmall.content.mapper.BlogLikeMapper;
import com.llmall.content.mapper.BlogMapper;
import com.llmall.content.mq.ContentEventPublisher;
import com.llmall.content.service.IBlogService;
import com.llmall.content.service.IFollowService;
import com.llmall.content.support.TxAfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.llmall.content.constant.ContentRedisConstants.BLOG_LIKED_KEY;
import static com.llmall.content.constant.ContentRedisConstants.BLOG_LIKED_TTL;
import static com.llmall.content.constant.ContentRedisConstants.CACHE_SET_MAX;

/**
 * 笔记的发布、可见性校验与互动（点赞、评论）。
 *
 * 发布评价时把 review.created 同事务登记到本地消息表、提交后再投递，门店域消费后重算热度；
 * 登记与投递的可靠性细节见 {@link ContentEventPublisher}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int STATUS_PUBLISHED = 1;

    private final BlogCommentsMapper blogCommentsMapper;
    private final BlogLikeMapper blogLikeMapper;
    private final ShopClient shopClient;
    private final StringRedisTemplate redisTemplate;
    private final ContentEventPublisher eventPublisher;
    private final IFollowService followService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishBlog(BlogFormDTO form, Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        // 门店存在性只能走 Feign：内容域不允许直连 llmall-shop 库（数据所有权原则）
        ShopDTO shop = shopClient.queryShopById(form.getShopId());
        if (shop == null) {
            throw new BizIllegalException("门店不存在: " + form.getShopId());
        }

        // blog.images 是 NOT NULL 且老库无默认值，而图文可选，这里统一兜底成空串
        Blog blog = new Blog()
                .setShopId(form.getShopId())
                .setUserId(userId)
                .setTitle(form.getTitle())
                .setContent(form.getContent())
                .setImages(StrUtil.blankToDefault(form.getImages(), ""))
                .setScore(form.getScore())
                .setLiked(0)
                .setComments(0)
                .setStatus(STATUS_PUBLISHED);
        save(blog);

        // 写扩散推送粉丝收件箱：粉丝过多时跳过，改由读时拉取
        followService.pushToFollowers(userId, blog.getId());

        // 【注意】不能把下面的调用套回 TxAfterCommit：publishReviewCreated 先在当前事务里 record()
        // 写 message_log，再 dispatchAfterCommit() 注册提交后回调；套上之后 record() 会被推到提交后
        // 执行、脱离业务事务，重新变成"业务已提交、消息可能没落库"，且提交后再注册 afterCommit 不会被执行。
        // 时序语义仍是提交后才发，只是多了"消息随业务同事务落库"的保证，失败记 FAILED 由 MessageRetryTask 重发。
        ReviewCreatedEvent event = new ReviewCreatedEvent()
                .setBizId(String.valueOf(blog.getId()))
                .setBlogId(blog.getId())
                .setShopId(blog.getShopId())
                .setUserId(userId)
                .setScore(blog.getScore())
                .setOccurredAt(System.currentTimeMillis());
        eventPublisher.publishReviewCreated(event);

        log.info("评价发布成功: blogId={}, shopId={}, score={}", blog.getId(), blog.getShopId(), blog.getScore());
        return blog.getId();
    }

    @Override
    public Blog queryDetail(Long id) {
        // 合规要求：对外只可见 status=1（已发布），见 requireVisibleBlog
        return requireVisibleBlog(id);
    }

    /**
     * 笔记可见性校验（合规）：仅 status=1（已发布）对外可见；status=0 待审核、2 已屏蔽对非作者
     * 统一按"不存在"处理，不返回"存在但被屏蔽"，否则审核形同虚设且会泄露审核状态；
     * 作者本人例外，可预览自己未过审的内容。
     * 【注意】身份取自 {@code user-info} 头，该头仅在携带内部凭证 {@code x-internal-token}
     * 时才被采信（见 UserInfoInterceptor），网关入口又会剥离客户端伪造的这两个头。
     */
    private Blog requireVisibleBlog(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            throw new BizIllegalException("笔记不存在: " + id);
        }
        if (!Integer.valueOf(STATUS_PUBLISHED).equals(blog.getStatus())) {
            Long current = UserContext.getUser();
            boolean isAuthor = current != null && current.equals(blog.getUserId());
            if (!isAuthor) {
                throw new BizIllegalException("笔记不存在: " + id);
            }
        }
        return blog;
    }

    @Override
    public PageDTO<Blog> pageByShop(Long shopId, Integer page, Integer size) {
        // 分页上下限统一走公共件：只挡 size<1 挡不住 size=100000 这种"一次拉走半个表"的请求
        int current = PageQuery.normalizePageNo(page);
        int pageSize = PageQuery.normalizeSize(size, 10);
        Page<Blog> result = lambdaQuery()
                .eq(Blog::getShopId, shopId)
                .eq(Blog::getStatus, STATUS_PUBLISHED)
                .orderByDesc(Blog::getCreateTime)
                .page(new Page<>(current, pageSize));
        return PageDTO.of(result, Blog.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int likeBlog(Long blogId, Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        Blog blog = getById(blogId);
        if (blog == null) {
            throw new BizIllegalException("笔记不存在: " + blogId);
        }
        try {
            blogLikeMapper.insert(new BlogLike().setBlogId(blogId).setUserId(userId));
        } catch (DuplicateKeyException e) {
            // 唯一索引挡住重复点赞 → 天然幂等，直接返回当前点赞数
            log.info("重复点赞，忽略: blogId={}, userId={}", blogId, userId);
            return nz(blog.getLiked());
        }
        // 计数用 SQL 自增，避免"读出来+1再写回"在并发下丢更新
        lambdaUpdate().eq(Blog::getId, blogId).setSql("liked = liked + 1").update();
        // ZSet 记录点赞人与时间，用于判重与点赞排行榜。
        // 【注意】写 Redis 必须在事务提交后：回滚不能在 Redis 留下"DB 没有的赞"；幂等靠唯一索引
        // uk_blog_user(blog_id,user_id) 挡重复点赞，ZADD 同 member 本就是覆盖写。
        TxAfterCommit.run("like blog=" + blogId + " user=" + userId, () -> {
            String key = BLOG_LIKED_KEY + blogId;
            // ZADD + EXPIRE 合成一次 Lua 往返：两条独立命令之间失败会留下没有 TTL 的排行榜 key，
            // 而被赞过的笔记数无界，现役 Redis 是 maxmemory 0 + noeviction，无 TTL 的 key
            // 既不过期也不淘汰，只会一路堆到写命令全部报错。
            try {
                redisTemplate.execute(ContentRedisScripts.ZSET_ADD_WITH_TTL,
                        Collections.singletonList(key),
                        String.valueOf(userId), String.valueOf(System.currentTimeMillis()),
                        String.valueOf(BLOG_LIKED_TTL.toMillis()));
            } catch (Exception e) {
                // 缓存写失败不影响点赞本身：DB 已提交，排行榜下次读会回源 blog_like 重建
                log.error("写入点赞排行榜失败（下次读会回源重建）: blogId={}, userId={}", blogId, userId, e);
            }
        });
        return nz(blog.getLiked()) + 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int unlikeBlog(Long blogId, Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        int removed = blogLikeMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogLike>()
                        .eq(BlogLike::getBlogId, blogId)
                        .eq(BlogLike::getUserId, userId));
        if (removed > 0) {
            lambdaUpdate().eq(Blog::getId, blogId)
                    .setSql("liked = GREATEST(liked - 1, 0)").update();
            // 同样等提交后再删，否则回滚会留下"DB 还点着赞、排行榜里人没了"的不一致
            TxAfterCommit.run("unlike blog=" + blogId + " user=" + userId, () -> {
                String key = BLOG_LIKED_KEY + blogId;
                // 与点赞同理：ZREM + 续期一次往返完成（见 ContentRedisScripts 的类注释）
                try {
                    redisTemplate.execute(ContentRedisScripts.ZSET_REMOVE_WITH_TTL,
                            Collections.singletonList(key),
                            String.valueOf(userId), String.valueOf(BLOG_LIKED_TTL.toMillis()));
                } catch (Exception e) {
                    log.error("移除点赞排行榜成员失败: blogId={}, userId={}", blogId, userId, e);
                }
            });
        }
        Blog blog = getById(blogId);
        return blog == null ? 0 : nz(blog.getLiked());
    }

    @Override
    public boolean isLiked(Long blogId, Long userId) {
        if (userId == null) {
            return false;
        }
        // 以 DB 为准（唯一索引是权威），Redis ZSet 只服务排行与快速判断
        return blogLikeMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogLike>()
                        .eq(BlogLike::getBlogId, blogId)
                        .eq(BlogLike::getUserId, userId)) != null;
    }

    @Override
    public List<Long> queryLikedUsers(Long blogId, Integer limit) {
        int size = (limit == null || limit <= 0) ? 5 : Math.min(limit, 50);
        // 该接口在网关白名单里（匿名可读）：已屏蔽/待审核的笔记连"点赞的人是谁"都不该再暴露
        requireVisibleBlog(blogId);
        String key = BLOG_LIKED_KEY + blogId;
        Set<String> users = redisTemplate.opsForZSet().reverseRange(key, 0, size - 1);
        if (CollUtils.isEmpty(users)) {
            // 缓存未命中（Redis 重启/被清/TTL 到期）→ 回源 DB 的 blog_like：
            // 排行榜就是"最近点赞的人"，blog_like 有 create_time，按时间倒序即同样的榜
            List<BlogLike> rows = blogLikeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogLike>()
                            .eq(BlogLike::getBlogId, blogId)
                            .orderByDesc(BlogLike::getCreateTime)
                            .last("LIMIT " + size));
            if (CollUtils.isEmpty(rows)) {
                return new ArrayList<>();
            }
            rebuildLikedZSet(blogId);
            List<Long> fallback = new ArrayList<>(rows.size());
            rows.forEach(r -> fallback.add(r.getUserId()));
            return fallback;
        }
        List<Long> result = new ArrayList<>();
        users.forEach(u -> result.add(Long.valueOf(u)));
        return result;
    }

    /**
     * 用 DB 回填点赞 ZSet（缓存自愈），只在缓存整体缺失时调用。
     * 【注意】必须整表重建：只写前 N 个点赞人会让 ZSet 成为"部分视图"，limit 更大时只能拿到
     * 这 N 个，返回偏少却不报错的榜，比完全不填更危险（错得静默）。
     * 超过 CACHE_SET_MAX 的笔记放弃回填：几万成员的 ZADD 会阻塞 Redis 单线程，
     * 而这条读路径回源一次 DB 本来就不贵。
     */
    private void rebuildLikedZSet(Long blogId) {
        Long total = blogLikeMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogLike>()
                        .eq(BlogLike::getBlogId, blogId));
        if (total == null || total == 0L) {
            return;
        }
        if (total > CACHE_SET_MAX) {
            log.debug("笔记 {} 点赞数 {} 超过缓存重建上限 {}，跳过 ZSet 回填",
                    blogId, total, CACHE_SET_MAX);
            return;
        }
        List<BlogLike> all = blogLikeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogLike>()
                        .eq(BlogLike::getBlogId, blogId));
        String key = BLOG_LIKED_KEY + blogId;
        // DEL + ZADD + EXPIRE 合成一次 Lua 往返：省掉 N 次网络往返，也避免中途失败留下"部分视图"
        String[] args = new String[all.size() * 2 + 1];
        args[0] = String.valueOf(BLOG_LIKED_TTL.toMillis());
        int i = 1;
        for (BlogLike like : all) {
            long score = like.getCreateTime() == null
                    ? System.currentTimeMillis()
                    : like.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            args[i++] = String.valueOf(like.getUserId());
            args[i++] = String.valueOf(score);
        }
        redisTemplate.execute(ContentRedisScripts.ZSET_REBUILD_WITH_TTL,
                Collections.singletonList(key), (Object[]) args);
        log.info("点赞 ZSet 缓存重建: blogId={}, size={}", blogId, all.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long comment(Long blogId, Long userId, String content, Long parentId, Long answerId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        if (getById(blogId) == null) {
            throw new BizIllegalException("笔记不存在: " + blogId);
        }
        BlogComments comments = new BlogComments()
                .setBlogId(blogId)
                .setUserId(userId)
                .setContent(content)
                .setParentId(parentId == null ? 0L : parentId)
                .setAnswerId(answerId == null ? 0L : answerId)
                .setLiked(0)
                .setStatus(0);
        blogCommentsMapper.insert(comments);
        lambdaUpdate().eq(Blog::getId, blogId).setSql("comments = comments + 1").update();
        return comments.getId();
    }

    @Override
    public PageDTO<BlogComments> pageComments(Long blogId, Integer page, Integer size) {
        // 同 pageByShop：评论是"外部可写、数量无上限"的数据，更不能让调用方自己决定单页多大
        int current = PageQuery.normalizePageNo(page);
        int pageSize = PageQuery.normalizeSize(size, 10);
        // 【注意】只过滤评论自身的 status 不够：该接口同样在网关白名单里（匿名可读），
        // 被屏蔽（或还没过审）的笔记只要 URL 猜得到，评论列表就能被整页读出。
        requireVisibleBlog(blogId);
        Page<BlogComments> result = blogCommentsMapper.selectPage(new Page<>(current, pageSize),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogComments>()
                        .eq(BlogComments::getBlogId, blogId)
                        // 只出 status=0（正常）的评论：1 被举报、2 禁止查看 若照样返回，评论审核就白做。
                        // 口径以本模块建表语句与 BlogComments 字段注释为准，新增评论也写 0，读写一致。
                        .eq(BlogComments::getStatus, 0)
                        .orderByDesc(BlogComments::getCreateTime));
        return PageDTO.of(result, BlogComments.class);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
