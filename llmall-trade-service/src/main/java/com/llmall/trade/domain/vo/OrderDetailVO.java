package com.llmall.trade.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 订单明细条目 VO（订单列表/详情页展示用）。
 *
 * 下单时明细已经冗余了商品名称/价格/图片（价格必须"下单一刻"快照，
 * 否则商品改价后历史订单金额会跟着变），所以这里直接用订单明细表的数据，
 * 不需要再远程调用 llmall-item-service —— 列表页 N 个订单也不会产生 N 次跨服务调用。
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
