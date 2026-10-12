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
 * <p>为什么在一个"跨服务 DTO"上加校验注解：llmall-item-service 的写接口（POST/PUT /items）
 * 直接收这个对象并落库，而 item 表的 name/price/stock 都是 NOT NULL、
 * name 是 varchar(200)、stock 是 int unsigned（负数会直接报错）。
 * 不在这里写边界，就会出现"接口 200、库里报错"或者更糟的"负数库存落库成功"。
 *
 * <p>这些注解只在被 {@code @Valid} 标注的**请求入参**上生效，
 * 作为 Feign/接口的**返回值**时不会触发校验，不影响任何查询链路。
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
    // 0 元是合法的（赠品/兑换券），负数不是：负单价会直接把订单总价算成负数
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
