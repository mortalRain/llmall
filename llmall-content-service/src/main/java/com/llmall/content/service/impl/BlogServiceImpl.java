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
import java.util.List;
import java.util.Set;

import static com.llmall.content.constant.ContentRedisConstants.BLOG_LIKED_KEY;
import static com.llmall.content.constant.ContentRedisConstants.BLOG_LIKED_TTL;
import static com.llmall.content.constant.ContentRedisConstants.CACHE_SET_MAX;

/**
 * 内容域服务实现。
 *
 * 亮点 C 的起点就在这里：发布评价 → 落库 → **同事务登记到本地消息表** → 提交后投递
 * review.created 事件 → 门店域消费并重算热度 → 榜单与列表排序随之变化。
 * 登记与投递的可靠性细节见 {@link ContentEventPublisher}（审计 B4 生产端）。
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
        // 1. 跨服务校验门店存在 —— 内容域不允许直接查 llmall-shop 库，只能走 Feign（数据所有权原则）
        ShopDTO shop = shopClient.queryShopById(form.getShopId());
        if (shop == null) {
            throw new BizIllegalException("门店不存在: " + form.getShopId());
        }

        // 2. 落库
        // blog.images 是 NOT NULL 且老库无默认值，图文可选，所以这里统一兜底成空串
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

        // 3. 推送到粉丝收件箱（写扩散，粉丝过多则跳过，改由读时拉取）
        followService.pushToFollowers(userId, blog.getId());

        // 4. 登记事件并**提交后**投递（审计 B4 生产端改造点）
        //
        //    这里的调用**故意不再套 TxAfterCommit**：publishReviewCreated 内部做两件事 ——
        //    ① record()：在当前事务里把 review.created 写进 llmall-content.message_log；
        //    ② dispatchAfterCommit()：注册 afterCommit 回调，提交之后才真正 convertAndSend。
        //    如果套回 TxAfterCommit.run(...)，record() 就会被推到提交之后执行、脱离业务事务，
        //    于是又变成"业务已提交、消息可能没落库"（提交后崩溃即丢），而且进了 afterCommit
        //    再注册 afterCommit 是嵌套注册，那个回调不会被执行。
        //    时序上仍然是"提交后才发"（语义不变），只是多了一层"消息已随业务同事务落库"的保证：
        //    MQ 抖动/提交后崩溃不再等于丢事件（失败会记 FAILED + 退避，由 MessageRetryTask 重发）。
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
     * 笔记可见性校验（合规，审计 B5 第 5 条）。
     *
     * <p>0 待审核、2 已屏蔽的内容如果匿名可读，审核就等于形同虚设 ——
     * 内容被下架后仍能通过直连 URL/收藏链接访问到。
     * 作者本人例外（可预览自己待审核的内容）；对其他人统一按"不存在"处理，
     * 不返回"这条内容存在但被屏蔽了"，避免泄露审核状态。
     *
     * <p>注意：这里读的登录身份来自 {@code user-info} 请求头，而该头只有在携带内部凭证
     * {@code x-internal-token} 时才被采信（见 UserInfoInterceptor），网关入口又会剥离客户端伪造的这两个头，
     * 所以"作者例外"不会被外部调用者伪造成任意作者。
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
        // ZSet 记录点赞人与时间：既能判重、又能做"点赞排行榜"。
        // 写 Redis 必须在事务提交之后：点赞事务一旦回滚，Redis 里不能留下这个"DB 没有的赞"。
        // 幂等性不受影响：唯一索引 uk_blog_user(blog_id,user_id) 挡住重复点赞，ZADD 同 member 也是覆盖写。
        TxAfterCommit.run("like blog=" + blogId + " user=" + userId, () -> {
            String key = BLOG_LIKED_KEY + blogId;
            redisTemplate.opsForZSet().add(key, String.valueOf(userId), System.currentTimeMillis());
            // ZSet 也必须带 TTL，否则每篇被赞过的笔记都会永久留一个 key（见 ContentRedisConstants）
            redisTemplate.expire(key, BLOG_LIKED_TTL);
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
                    // GREATEST 兜底，避免出现负数点赞数
                    .setSql("liked = GREATEST(liked - 1, 0)").update();
            // 同样等提交后再删：否则事务回滚会留下"DB 还点着赞、排行榜里人没了"的不一致
            TxAfterCommit.run("unlike blog=" + blogId + " user=" + userId, () -> {
                String key = BLOG_LIKED_KEY + blogId;
                redisTemplate.opsForZSet().remove(key, String.valueOf(userId));
                redisTemplate.expire(key, BLOG_LIKED_TTL);
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
        // 以 DB 为准（唯一索引是权威），Redis ZSet 用于排行与快速判断
        return blogLikeMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogLike>()
                        .eq(BlogLike::getBlogId, blogId)
                        .eq(BlogLike::getUserId, userId)) != null;
    }

    @Override
    public List<Long> queryLikedUsers(Long blogId, Integer limit) {
        int size = (limit == null || limit <= 0) ? 5 : Math.min(limit, 50);
        // 这个接口在网关白名单里（GET /blogs/*/liked-users，匿名可读）：
        // 已屏蔽/待审核的笔记连"点赞的人是谁"都不该再暴露出去
        requireVisibleBlog(blogId);
        String key = BLOG_LIKED_KEY + blogId;
        Set<String> users = redisTemplate.opsForZSet().reverseRange(key, 0, size - 1);
        if (CollUtils.isEmpty(users)) {
            // 缓存未命中（Redis 重启/被清/TTL 到期）→ 回源 DB 的 blog_like。
            // 排行榜是"最近点赞的人"，blog_like 有 create_time，直接按时间倒序就是同样的榜
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
     * 用 DB 回填点赞 ZSet（缓存自愈）。
     *
     * <p>只在"缓存整体缺失"时调用，且**必须整表重建**：如果只把前 N 个点赞人写进去，
     * ZSet 就成了"部分视图"，下次有人请求 limit=50 时 reverseRange 只能拿到这 N 个，
     * 返回一个偏少但不报错的榜 —— 部分填充比完全不填更危险（错得静默）。
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
        all.forEach(like -> {
            long score = like.getCreateTime() == null
                    ? System.currentTimeMillis()
                    : like.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            redisTemplate.opsForZSet().add(key, String.valueOf(like.getUserId()), score);
        });
        redisTemplate.expire(key, BLOG_LIKED_TTL);
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
        // 【补漏】光过滤评论自身的 status 不够：这个接口同样在白名单里（GET /blogs/*/comments），
        // 一条被屏蔽（或还没过审）的笔记，只要 URL 猜得到，它的评论列表照样能整页读出来 ——
        // 笔记详情已经挡住了，评论列表却还开着后门（审计 B5"只改了一半"指的就是这里）。
        requireVisibleBlog(blogId);
        Page<BlogComments> result = blogCommentsMapper.selectPage(new Page<>(current, pageSize),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogComments>()
                        .eq(BlogComments::getBlogId, blogId)
                        // 只出 status=0（正常）的评论：1 被举报、2 禁止查看 的评论如果照样返回，
                        // "评论审核"就白做了 —— 违规内容会一直在详情页挂着。
                        // 口径以本模块的建表语句与 BlogComments 字段注释为准（0 正常 / 1 被举报 / 2 禁止查看），
                        // 新增评论也写 0（见 comment 方法），读写两侧一致。
                        .eq(BlogComments::getStatus, 0)
                        .orderByDesc(BlogComments::getCreateTime));
        return PageDTO.of(result, BlogComments.class);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
