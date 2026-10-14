package com.llmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单详情：订单内的商品明细行，含 sku、数量、单价与规格。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("order_detail")
public class OrderDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** sku商品id */
    private Long itemId;

    private Integer num;

    /** 商品标题 */
    private String name;

    /** 商品动态属性键值集 */
    private String spec;

    /** 价格，单位分 */
    private Integer price;

    /** 商品图片 */
    private String image;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}
