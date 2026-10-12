package com.llmall.item.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;

import com.llmall.common.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "商品分页查询条件")
public class ItemPageQuery extends PageQuery {
    @Schema(description = "搜索关键字")
    private String key;
    @Schema(description = "商品分类")
    private String category;
    @Schema(description = "商品品牌")
    private String brand;
    @Schema(description = "价格最小值")
    private Integer minPrice;
    @Schema(description = "价格最大值")
    private Integer maxPrice;
    @Schema(description = "所属门店 id（查门店套餐）")
    private Long shopId;
    @Schema(description = "1 实物商品 2 门店套餐/团购券")
    private Integer type;
    @Schema(description = "商品状态：1 上架 2 下架 3 删除；不传默认只查上架")
    private Integer status;
}
