package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.io.Serializable;

/**
 * 门店 DTO（跨服务传输用，不是数据库实体）。
 *
 * 约定：服务之间只传 DTO，不传各自的 PO，避免"别人改了我的表结构，我的服务跟着挂"。
 */
@Data
@Schema(description = "门店信息")
public class ShopDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "门店 id")
    private Long id;

    @Schema(description = "门店名称")
    private String name;

    @Schema(description = "门店类型 id")
    private Long typeId;

    @Schema(description = "门店类型名称")
    private String typeName;

    @Schema(description = "门店图片，多张以逗号分隔")
    private String images;

    @Schema(description = "商圈")
    private String area;

    @Schema(description = "地址")
    private String address;

    @Schema(description = "经度")
    private Double x;

    @Schema(description = "纬度")
    private Double y;

    @Schema(description = "人均价格（分）")
    private Long avgPrice;

    @Schema(description = "销量")
    private Integer sold;

    @Schema(description = "评价数")
    private Integer comments;

    @Schema(description = "评分 x10（45 表示 4.5 分）")
    private Integer score;

    @Schema(description = "热度分（时间衰减后的综合评分）")
    private Double hotScore;

    @Schema(description = "营业时间")
    private String openHours;

    @Schema(description = "状态 1 营业中 2 休息中 3 已下线")
    private Integer status;

    @Schema(description = "距离（公里，仅附近查询返回）")
    private Double distance;
}
