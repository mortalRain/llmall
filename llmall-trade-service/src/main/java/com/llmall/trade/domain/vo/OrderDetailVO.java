package com.llmall.trade.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 订单明细条目 VO（订单列表/详情页展示用）。
 *
 * 【注意】价格是"下单一刻"的快照，否则商品改价后历史订单金额会跟着变；明细已冗余名称/价格/图片，
 * 直接读明细表即可，列表页 N 个订单不产生 N 次跨服务调用。
 */
@Data
@Schema(description = "订单明细条目VO")
public class OrderDetailVO {
    @Schema(description = "明细id")
    private Long id;
    @Schema(description = "订单id")
    private Long orderId;
    @Schema(description = "商品/SKU id")
    private Long itemId;
    @Schema(description = "商品名称")
    private String name;
    @Schema(description = "规格")
    private String spec;
    @Schema(description = "下单时单价（分）")
    private Integer price;
    @Schema(description = "购买数量")
    private Integer num;
    @Schema(description = "商品图片")
    private String image;
}
