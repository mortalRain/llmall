package com.llmall.item.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品表
 *
 * @since 2023-05-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("item")
public class Item implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属门店 id（本地生活场景：门店卖套餐，原商城无此概念） */
    private Long shopId;

    /** 1 实物商品 2 门店套餐/团购券 */
    private Integer type;

    /** SKU 名称 */
    private String name;

    /** 价格，单位：分 */
    private Integer price;

    private Integer stock;

    private String image;

    private String category;

    private String brand;

    private String spec;

    private Integer sold;

    private Integer commentCount;

    @TableField("isAD")
    private Boolean isAD;

    /** 商品状态：1 正常 2 下架 3 删除 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;


}
