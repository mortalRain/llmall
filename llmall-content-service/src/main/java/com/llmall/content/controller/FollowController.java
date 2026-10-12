package com.llmall.content.controller;

import com.llmall.common.utils.UserContext;
import com.llmall.content.service.IFollowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @Operation(summary = "我的 Feed 流（滚动分页：传上一页最后一条 id）")
    @GetMapping("/feed")
    public List<Long> feed(@RequestParam(value = "lastId", required = false) Long lastId,
                           @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        return followService.queryFeed(UserContext.getUser(), lastId, size);
    }
}
