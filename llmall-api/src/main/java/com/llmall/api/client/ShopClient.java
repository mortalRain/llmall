package com.llmall.api.client;

import com.llmall.api.dto.ShopDTO;
import com.llmall.api.dto.ShopPageDTO;
import com.llmall.api.fallback.ShopClientFallback;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;

/**
 * 门店服务 Feign 客户端；使用方：内容域（校验门店、展示笔记所属门店）、搜索域（构建门店索引）、
 * 交易域（团购券订单需要门店信息）。
 */
@FeignClient(value = "llmall-shop-service", fallbackFactory = ShopClientFallback.class)
public interface ShopClient {

    @Operation(summary = "查询门店详情")
    @GetMapping("/shops/{id}")
    ShopDTO queryShopById(@PathVariable("id") Long id);

    @Operation(summary = "批量查询门店")
    @GetMapping("/shops/batch")
    List<ShopDTO> queryShopByIds(@RequestParam("ids") Collection<Long> ids);

    @Operation(summary = "对账用：查询某订单的门店销量是否已累加")
    @GetMapping("/shops/orders/{orderId}/consumed")
    boolean isOrderConsumed(@PathVariable("orderId") Long orderId);

    /**
     * 判断用户是否为门店员工（内部接口，到店核销的判权依据）。
     *
     * 【注意】这是判权接口，不是展示接口：调用方（交易域的核销入口）必须把"取不到明确答案"
     * 一律当成拒绝（见 ShopClientFallback 的降级实现），不能因为返回 false 与调用失败难以区分
     * 就默认放行 —— 那正是修复前的越权路径。
     */
    @Operation(summary = "判断用户是否为指定门店的员工（内部接口，需服务间凭证）")
    @GetMapping("/internal/shops/{shopId}/staff/{userId}")
    boolean isStaff(@PathVariable("shopId") Long shopId, @PathVariable("userId") Long userId);

    @Operation(summary = "分页查询门店（供搜索域构建本地搜索视图）")
    @GetMapping("/shops/page")
    ShopPageDTO queryShopPage(@RequestParam("page") Integer page, @RequestParam("size") Integer size);
}
