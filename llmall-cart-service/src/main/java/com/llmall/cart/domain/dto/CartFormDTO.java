package com.llmall.cart.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 新增购物车商品表单实体。
 *
 * 【注意】长度上限与库表 varchar 对齐（name/image 256、spec 1024）：MySQL 打开 STRICT_TRANS_TABLES 时
 * 超长写入不是"截断"而是直接报错，校验写在入参上才能变成前端可展示的中文提示。
 */
@Data
@Schema(description = "新增购物车商品表单实体")
public class CartFormDTO {
    @Schema(description = "商品id")
    @NotNull(message = "商品id不能为空")
    private Long itemId;
    @Schema(description = "商品标题")
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 256, message = "商品名称过长（最多256字）")
    private String name;
    @Schema(description = "商品动态属性键值集")
    @Size(max = 1024, message = "商品规格过长（最多1024字）")
    private String spec;
    @Schema(description = "价格,单位：分")
    @NotNull(message = "商品价格不能为空")
    // 0 元合法（赠品/兑换券）；负单价会把结算总价算成负数
    @Min(value = 0, message = "商品价格不能为负数")
    private Integer price;
    @Schema(description = "商品图片")
    @Size(max = 256, message = "商品图片地址过长（最多256字）")
    private String image;
}
