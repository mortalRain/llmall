package com.llmall.cart.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 购物车条目，对应 cart 表。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("cart")
public class Cart implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** SKU 商品 id */
    private Long itemId;

    private Integer num;

    private String name;

    /** 商品动态属性键值集 */
    private String spec;

    /** 价格，单位：分 */
    private Integer price;

    private String image;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}
