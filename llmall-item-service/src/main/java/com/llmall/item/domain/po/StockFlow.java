package com.llmall.item.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存变动流水：每次库存变动留一条记录，供幂等判定与对账补偿逐笔比对。
 *
 * 【注意】(biz_type, biz_id, item_id) 唯一索引同时承担两个职责：幂等闸门 + 对账依据。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("stock_flow")
public class StockFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long itemId;

    /** 冗余门店 id，便于按门店统计与对账 */
    private Long shopId;

    /** 变动数量：负数=扣减，正数=回滚/入库 */
    private Integer changeNum;

    /** 业务类型：ORDER_CREATED / ORDER_CANCELED / SECKILL */
    private String bizType;

    /** 业务主键（订单号） */
    private String bizId;

    /** 1 有效 2 已回滚 */
    private Integer status;

    private LocalDateTime createTime;
}
