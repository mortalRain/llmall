package com.llmall.content.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.common.domain.PageDTO;
import com.llmall.content.domain.dto.BlogFormDTO;
import com.llmall.content.domain.po.Blog;
import com.llmall.content.domain.po.BlogComments;

import java.util.List;

public interface IBlogService extends IService<Blog> {

    /** 发布探店笔记/评价：落库 + 投递评价事件 + 推送粉丝 Feed */
    Long publishBlog(BlogFormDTO form, Long userId);

    /** 笔记详情 */
    Blog queryDetail(Long id);

    /** 门店下的笔记分页 */
    PageDTO<Blog> pageByShop(Long shopId, Integer page, Integer size);

    /** 点赞（幂等）；返回最新点赞数 */
    int likeBlog(Long blogId, Long userId);

    /** 取消点赞；返回最新点赞数 */
    int unlikeBlog(Long blogId, Long userId);

    /** 当前用户是否已点赞 */
    boolean isLiked(Long blogId, Long userId);

    /** 点赞用户排行（Redis ZSet，按点赞时间倒序） */
    List<Long> queryLikedUsers(Long blogId, Integer limit);

    /** 发表评论 */
    Long comment(Long blogId, Long userId, String content, Long parentId, Long answerId);

    /** 评论分页 */
    PageDTO<BlogComments> pageComments(Long blogId, Integer page, Integer size);
}
