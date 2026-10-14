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
    @PutMapping("/items/stock/deduct")
    void deductStock(@RequestBody List<OrderDetailDTO> items);

    @Operation(summary = "批量回滚库存（订单取消/超时未支付）")
    @PutMapping("/items/stock/restore")
    void restoreStock(@RequestBody List<OrderDetailDTO> items);

    /**
     * 查询某个业务号（通常是订单号）在库存服务这边到底扣没扣。
     *
     * 供跨服务对账使用：扣库存请求可能"扣成功了但回包丢了"，此时下单方事务回滚、订单不存在，
     * 而库存已经少了；下单方需先确认下游是否真的扣减，确认扣了才补回库存。
     *
     * @param bizId   业务号（下单时传的是订单 id）
     * @param bizType 业务类型，取 {@code ORDER_CREATED}（与扣减时写入流水的一致）
     */
    @Operation(summary = "查询某业务号的库存是否已扣减")
    @GetMapping("/items/stock/deducted")
    boolean isStockDeducted(@RequestParam("bizId") String bizId, @RequestParam("bizType") String bizType);
}
