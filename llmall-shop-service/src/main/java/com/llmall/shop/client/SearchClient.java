package com.llmall.shop.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 搜索服务 Feign 客户端（对账专用）。
 *
 * 【注意】返回值是三态口径：true 已处理 / false 明确未处理 / 抛异常 = 不知道（搜索域不可用或超时）。
 * 调用方必须把异常当"未知"，既不能当"未处理"（会引发补偿风暴），也不能当"已处理"（会掩盖一致性缺口）。
 * 本客户端只服务对账补发，不参与门店主流程：搜索域不可用时门店业务照常，只影响对账顺延到下一轮。
 */
@FeignClient(value = "llmall-search-service")
public interface SearchClient {

    /**
     * 查询某条门店文档变更事件是否已被搜索域消费。
     *
     * @param bizType 业务类型（固定 SHOP_HOT_CHANGED）
     * @param bizId   事件幂等键（门店 id + 毫秒时间戳，与 message_log.biz_id 一致）
     */
    @GetMapping("/search/consume-status")
    ConsumeStatusDTO isConsumed(@RequestParam("bizType") String bizType,
                                @RequestParam("bizId") String bizId);
}
