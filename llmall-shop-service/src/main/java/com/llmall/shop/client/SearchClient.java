package com.llmall.shop.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 搜索服务 Feign 客户端（**对账专用**，审计 B4）。
 *
 * 为什么放在 llmall-shop-service 自己的模块里，而不是 llmall-api：
 * 本次改动不允许修改 llmall-api / llmall-common，所以这个"只被门店域用于对账"的契约就地声明。
 *
 * 为什么对账要走"问下游"而不是"看自己发成功了没有"：
 * MQ 的发送成功只说明消息进了 broker，**不代表下游处理成功**（消费者处理到一半宕机、
 * ack 丢失、消息进了死信，发送方全都看不到）。所以对账必须问下游：
 * 这条 shop.hot.changed 你到底写进消费记录（consume_record）了没有。
 *
 * 返回值口径：true = 已处理；false = 明确未处理；**抛异常 = 不知道**（下游挂了/超时），
 * 调用方必须把异常当成"未知"而不是"未处理"，也不能当成"已处理"——
 * 这正是审计里反复强调的三态口径（见 ShopSearchReconcileTask 的注释）。
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
