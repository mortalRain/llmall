package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 商品实体（既是商品域返回给前端的视图，也是新增/更新商品的入参）。
 *
 * 校验注解的边界与 item 表对齐：name/price/stock 均为 NOT NULL、name 为 varchar(200)、
 * stock 为 int unsigned，缺少边界会出现接口 200 而落库报错，或负数库存落库成功。
 *
 * 【注意】这些注解只在被 @Valid 标注的请求入参上生效，作为 Feign 返回值时不触发校验。
 */
@Data
@Schema(description = "商品实体")
public class ItemDTO {
    @Schema(description = "商品id")
    private Long id;
    @Schema(description = "所属门店 id（本地生活场景：门店卖套餐）")
    private Long shopId;
    @Schema(description = "1 实物商品 2 门店套餐/团购券")
    private Integer type;
    @Schema(description = "SKU名称")
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 200, message = "商品名称过长（最多200字）")
    private String name;
    @Schema(description = "价格（分）")
    @NotNull(message = "商品价格不能为空")
    // 0 元合法（赠品/兑换券）；负单价会把订单总价算成负数
    @Min(value = 0, message = "商品价格不能为负数")
    private Integer price;
    @Schema(description = "库存数量")
    @NotNull(message = "商品库存不能为空")
    @Min(value = 0, message = "商品库存不能为负数")
    private Integer stock;
    @Schema(description = "商品图片")
    @Size(max = 200, message = "商品图片地址过长（最多200字）")
    private String image;
    @Schema(description = "类目名称")
    @Size(max = 200, message = "商品类目过长（最多200字）")
    private String category;
    @Schema(description = "品牌名称")
    @Size(max = 100, message = "商品品牌过长（最多100字）")
    private String brand;
    @Schema(description = "规格")
    @Size(max = 200, message = "商品规格过长（最多200字）")
    private String spec;
    @Schema(description = "销量")
    private Integer sold;
    @Schema(description = "评论数")
    private Integer commentCount;
    @Schema(description = "是否是推广广告，true/false")
    private Boolean isAD;
    @Schema(description = "商品状态 1-正常，2-下架，3-删除")
    private Integer status;
}
