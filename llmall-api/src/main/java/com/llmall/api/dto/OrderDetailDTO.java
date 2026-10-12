package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 订单明细条目（库存扣减请求体）。
 *
 * 新增 bizType / bizId 两个字段（可选、向后兼容）：
 * 库存服务要写"库存流水"才能做对账与幂等，(bizType, bizId, itemId) 唯一索引即是幂等闸门；
 * 不传这两个字段时按原有逻辑扣减，不影响既有调用方。
 */
@Schema(description = "订单明细条目")
@Data
@Accessors(chain = true)
public class OrderDetailDTO {
    /**
     * 【安全】数量必须 >= 1。
     *
     * 为什么这个下界是安全底线而不是业务偏好：扣库存的 SQL 是 `stock = stock - #{num}`，
     * 一旦 num 为负数，减号会把语句**反转成加库存**，而守卫 `stock >= num`（num 为负时恒真）
     * 也同时失效 —— 一个负号就能把"防超卖"变成"无限刷库存"。
     * 回滚接口 `stock = stock + #{num}` 更糟：负数量会把库存扣成负数并把 sold 抬高。
     *
     * 但只加注解**不够**：这两个方法也走 Feign 内部调用（ItemClient），
     * 而 Feign 消费端不会触发 Bean Validation，注解在调用链上是"没有读者"的。
     * 因此 ItemServiceImpl 里必须再做一次显式校验（见该处注释），注解只是给 HTTP/文档入口的第一道拦网。
     */
    @Schema(description = "商品id")
    @NotNull(message = "商品id不能为空")
    private Long itemId;

    @Schema(description = "商品购买数量，必须大于 0")
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于 0")
    private Integer num;

    @Schema(description = "业务类型，如 ORDER_CREATED / ORDER_CANCELED / SECKILL（可选）")
    private String bizType;

    @Schema(description = "业务主键（订单号等，可选）；传入即启用幂等与流水记录")
    private String bizId;

    @Schema(description = "所属门店 id（可选，冗余用于统计与对账）")
    private Long shopId;
}
