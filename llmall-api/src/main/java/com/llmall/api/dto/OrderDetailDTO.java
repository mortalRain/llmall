package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 订单明细条目（库存扣减请求体）。
 *
 * bizType / bizId 可选，用于写入库存流水；(bizType, bizId, itemId) 唯一索引即幂等闸门。
 */
@Schema(description = "订单明细条目")
@Data
@Accessors(chain = true)
public class OrderDetailDTO {
    /**
     * 【注意】数量必须 >= 1：扣库存的 SQL 为 stock = stock - #{num}，num 为负时减号会把语句
     * 反转成加库存，守卫 stock >= num（num 为负时恒真）也同时失效。
     *
     * 注解只在 HTTP/文档入口生效，Feign 内部调用不触发 Bean Validation，服务端必须再显式校验。
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
