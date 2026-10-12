package com.llmall.user.controller;

import com.llmall.common.utils.UserContext;
import com.llmall.user.domain.po.PointsFlow;
import com.llmall.user.service.IPointsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "积分接口")
@RestController
@RequestMapping("/points")
@RequiredArgsConstructor
public class PointsController {

    private final IPointsService pointsService;

    /**
     * 对账接口：交易域的对账任务通过它判断"这笔订单的积分到底发了没有"。
     * 内部 Feign 直连本服务（不经网关），因此不受网关鉴权路径配置影响。
     */
    @Operation(summary = "查询订单积分是否已发放（对账用）")
    @GetMapping("/orders/{orderId}/applied")
    public boolean isApplied(@PathVariable("orderId") Long orderId) {
        return pointsService.isApplied(orderId);
    }

    @Operation(summary = "我的积分流水")
    @GetMapping("/me")
    public List<PointsFlow> myFlows(@RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        return pointsService.listMyFlows(UserContext.getUser(), limit);
    }
}
