package com.llmall.content.controller;

import com.llmall.common.domain.PageDTO;
import com.llmall.common.utils.UserContext;
import com.llmall.content.domain.dto.BlogFormDTO;
import com.llmall.content.domain.po.Blog;
import com.llmall.content.domain.po.BlogComments;
import com.llmall.content.service.IBlogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 内容域接口（探店笔记 / 评价 / 点赞 / 评论）。
 *
 * 发布评价是亮点 C 的触发点：一次发布会让"门店热度 → 榜单 → 列表排序"发生变化。
 */
@Tag(name = "探店笔记与评价接口")
@RestController
@RequestMapping("/blogs")
@RequiredArgsConstructor
public class BlogController {

    private final IBlogService blogService;

    @Operation(summary = "发布探店笔记/评价")
    @PostMapping
    public Long publish(@RequestBody @Valid BlogFormDTO form) {
        return blogService.publishBlog(form, UserContext.getUser());
    }

    @Operation(summary = "查询笔记详情")
    @GetMapping("/{id}")
    public Blog queryById(@PathVariable("id") Long id) {
        return blogService.queryDetail(id);
    }

    @Operation(summary = "查询门店下的笔记")
    @GetMapping("/shop/{shopId}")
    public PageDTO<Blog> pageByShop(@PathVariable("shopId") Long shopId,
                                    @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                    @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        return blogService.pageByShop(shopId, page, size);
    }

    @Operation(summary = "点赞（幂等）")
    @PostMapping("/{id}/like")
    public int like(@PathVariable("id") Long id) {
        return blogService.likeBlog(id, UserContext.getUser());
    }

    @Operation(summary = "取消点赞")
    @DeleteMapping("/{id}/like")
    public int unlike(@PathVariable("id") Long id) {
        return blogService.unlikeBlog(id, UserContext.getUser());
    }

    @Operation(summary = "当前用户是否已点赞")
    @GetMapping("/{id}/liked")
    public boolean liked(@PathVariable("id") Long id) {
        return blogService.isLiked(id, UserContext.getUser());
    }

    @Operation(summary = "点赞用户排行（Redis ZSet）")
    @GetMapping("/{id}/liked-users")
    public List<Long> likedUsers(@PathVariable("id") Long id,
                                 @RequestParam(value = "limit", required = false, defaultValue = "5") Integer limit) {
        return blogService.queryLikedUsers(id, limit);
    }

    @Operation(summary = "发表评论")
    @PostMapping("/{id}/comments")
    public Long comment(@PathVariable("id") Long id, @RequestBody @Validated CommentForm form) {
        return blogService.comment(id, UserContext.getUser(), form.getContent(),
                form.getParentId(), form.getAnswerId());
    }

    @Operation(summary = "评论分页")
    @GetMapping("/{id}/comments")
    public PageDTO<BlogComments> comments(@PathVariable("id") Long id,
                                          @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                          @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        return blogService.pageComments(id, page, size);
    }

    @Data
    public static class CommentForm {
        @NotBlank(message = "评论内容不能为空")
        private String content;
        /** 父评论 id，0 为一级评论 */
        private Long parentId;
        /** 被回复的评论 id，0 为无 */
        private Long answerId;
    }
}
