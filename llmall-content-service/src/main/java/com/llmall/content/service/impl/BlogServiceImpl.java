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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.llmall.content.constant.ContentRedisConstants.BLOG_LIKED_KEY;

/**
 * 内容域服务实现。
 *
 * 亮点 C 的起点就在这里：发布评价 → 落库 → **提交后**投递 review.created 事件 →
 * 门店域消费并重算热度 → 榜单与列表排序随之变化。
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

        // 4. 事务提交后再投递事件
        ReviewCreatedEvent event = new ReviewCreatedEvent()
                .setBizId(String.valueOf(blog.getId()))
                .setBlogId(blog.getId())
                .setShopId(blog.getShopId())
                .setUserId(userId)
                .setScore(blog.getScore())
                .setOccurredAt(System.currentTimeMillis());
        publishAfterCommit(() -> eventPublisher.publishReviewCreated(event));

        log.info("评价发布成功: blogId={}, shopId={}, score={}", blog.getId(), blog.getShopId(), blog.getScore());
        return blog.getId();
    }

    /**
     * 事务提交后再执行：事务未提交就发消息，消费者可能先于提交读到数据（甚至读不到）。
     * 注意 afterCommit 只保证时序正确，不等于可靠投递 —— 提交后进程崩溃仍会丢消息，
     * 真正可靠需要本地消息表 + 重试 + 对账。
     */
    private void publishAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Override
    public Blog queryDetail(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            throw new BizIllegalException("笔记不存在: " + id);
        }
        // 合规要求：对外只可见 status=1（已发布）。0 待审核、2 已屏蔽的内容如果匿名可读，
        // 审核就等于形同虚设 —— 内容被下架后仍能通过直连 URL/收藏链接访问到。
        // 作者本人例外（可预览自己待审核的内容）；对其他人统一按"不存在"处理，
        // 不返回"这条内容存在但被屏蔽了"，避免泄露审核状态。
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
        // ZSet 记录点赞人与时间：既能判重、又能做"点赞排行榜"
        redisTemplate.opsForZSet().add(BLOG_LIKED_KEY + blogId, String.valueOf(userId), System.currentTimeMillis());
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
            redisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + blogId, String.valueOf(userId));
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
        Set<String> users = redisTemplate.opsForZSet().reverseRange(BLOG_LIKED_KEY + blogId, 0, size - 1);
        List<Long> result = new ArrayList<>();
        if (users != null) {
            users.forEach(u -> result.add(Long.valueOf(u)));
        }
        return result;
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
        Page<BlogComments> result = blogCommentsMapper.selectPage(new Page<>(current, pageSize),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BlogComments>()
                        .eq(BlogComments::getBlogId, blogId)
                        // 只出 status=0（正常）的评论：1 被举报、2 禁止查看 的评论如果照样返回，
                        // "评论审核"就白做了 —— 违规内容会一直在详情页挂着。
                        .eq(BlogComments::getStatus, 0)
                        .orderByDesc(BlogComments::getCreateTime));
        return PageDTO.of(result, BlogComments.class);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
