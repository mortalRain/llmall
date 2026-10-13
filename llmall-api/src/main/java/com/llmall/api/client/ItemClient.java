package com.llmall.api.client;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.api.fallback.ItemClientFallback;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;

@FeignClient(value = "llmall-item-service",fallbackFactory = ItemClientFallback.class)
public interface ItemClient {

    @GetMapping("/items")
    List<ItemDTO> queryItemByIds(@RequestParam("ids") Collection<Long> ids);

    @Operation(summary = "批量扣减库存")
    // 修复：原路径写成 "items//stock/deduct"（双斜杠），部分网关/客户端会 404
    @PutMapping("/items/stock/deduct")
    void deductStock(@RequestBody List<OrderDetailDTO> items);

    @Operation(summary = "批量回滚库存（订单取消/超时未支付）")
    @PutMapping("/items/stock/restore")
    void restoreStock(@RequestBody List<OrderDetailDTO> items);

    /**
     * 查询某个业务号（通常是订单号）在库存服务这边**到底扣没扣**。
     *
     * <p>【为什么需要它】扣库存是跨服务调用：请求可能"扣成功了但回包丢了"。
     * 此时下单方的事务回滚、订单不存在，而库存已经少了 —— 少卖且无从自动补偿。
     * 有了这个查询，下单方在事务回滚后可以先问一句"你到底扣没扣"，扣了才补回库存。
     * 这正是"问下游，而不是假设自己知道"的对账思路（与 {@code UserClient#isPointsApplied} 同一手法）。
     *
     * @param bizId   业务号（下单时传的是订单 id）
     * @param bizType 业务类型，取 {@code ORDER_CREATED}（与扣减时写入流水的一致）
     */
    @Operation(summary = "查询某业务号的库存是否已扣减")
    @GetMapping("/items/stock/deducted")
    boolean isStockDeducted(@RequestParam("bizId") String bizId, @RequestParam("bizType") String bizType);
}
