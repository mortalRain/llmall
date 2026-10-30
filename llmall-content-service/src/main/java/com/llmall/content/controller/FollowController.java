package com.llmall.content.controller;

import com.llmall.common.utils.UserContext;
import com.llmall.content.domain.vo.FeedItemVO;
import com.llmall.content.domain.vo.FeedPageVO;
import com.llmall.content.service.IFollowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Tag(name = "关注与 Feed 流接口")
@RestController
@RequestMapping("/follows")
@RequiredArgsConstructor
public class FollowController {

    private final IFollowService followService;

    @Operation(summary = "关注用户")
    @PostMapping("/{userId}")
    public void follow(@PathVariable("userId") Long userId) {
        followService.follow(UserContext.getUser(), userId);
    }

    @Operation(summary = "取消关注")
    @DeleteMapping("/{userId}")
    public void unfollow(@PathVariable("userId") Long userId) {
        followService.unfollow(UserContext.getUser(), userId);
    }

    @Operation(summary = "是否已关注")
    @GetMapping("/{userId}/is-followed")
    public boolean isFollowed(@PathVariable("userId") Long userId) {
        return followService.isFollowed(UserContext.getUser(), userId);
    }

    @Operation(summary = "共同关注（Redis 集合求交集）")
    @GetMapping("/common/{userId}")
    public Set<String> common(@PathVariable("userId") Long userId) {
        return followService.commonFollows(UserContext.getUser(), userId);
    }

    @Operation(summary = "粉丝数")
    @GetMapping("/{userId}/fans")
    public long fans(@PathVariable("userId") Long userId) {
        return followService.followerCount(userId);
    }

    /**
     * 我的 Feed 流：返回完整内容（笔记详情 + 作者 id），一次请求即拿到可渲染的数据。
     *
     * 【游标】lastId 传上一页最后一条的 feedId（收件箱记录 id），首页不传。
     * 【为什么返回完整内容】只返回 blogId 时，客户端要为每条笔记各发一次详情请求，
     * 阅读放大从服务端挪到了网络侧；服务端已用一条 IN 查询把整页笔记批量取回并合并。
     * 【兼容】只需要 id 列表的调用方走 {@link #feedIds}（同一份收件箱查询，形状不变）。
     */
    @Operation(summary = "我的 Feed 流（滚动分页：传上一页最后一条的 feedId，返回笔记详情）")
    @GetMapping("/feed")
    public List<FeedItemVO> feed(@RequestParam(value = "lastId", required = false) Long lastId,
                                 @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        return followService.queryFeedItems(UserContext.getUser(), lastId, size);
    }

    /** 我的 Feed 流（仅 id 列表）：供只要 id、自己再取详情的调用方使用，滚动分页口径与上面的 feed 一致 */
    @Operation(summary = "我的 Feed 流（仅收件箱记录对应的笔记 id 列表）")
    @GetMapping("/feed/ids")
    public List<Long> feedIds(@RequestParam(value = "lastId", required = false) Long lastId,
                              @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        return followService.queryFeed(UserContext.getUser(), lastId, size);
    }

    /**
     * 我的 Feed 流 v2：收件箱（推）与大V 补拉按笔记发布时间/id归并。
     *
     * 首页不传游标，后续成对回传nextCt/nextCi；用hasMore判断是否继续。
     * li/ai仅兼容旧参数，不能单独用于翻页。
     * 【与 /feed 的关系】/feed 保持纯推语义不变，v2 是新增能力，两者不改变对方口径。
     */
    @Operation(summary = "我的 Feed 流 v2（推拉结合：收件箱 + 大V 补拉合并，复合游标）")
    @GetMapping("/feed/v2")
    public FeedPageVO feedV2(@RequestParam(value = "li", required = false) Long li,
                             @RequestParam(value = "ct", required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime ct,
                             @RequestParam(value = "ci", required = false) Long ci,
                             @RequestParam(value = "ai", required = false) Long ai,
                             @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        return followService.queryFeedItemsV2(UserContext.getUser(), li, ct, ci, ai, size);
    }
}
