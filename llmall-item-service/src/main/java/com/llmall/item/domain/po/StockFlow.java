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

    /**
     * 流水状态「有效」，也是写入路径唯一的取值。
     *
     * 【为什么要有这个名字】写入点若直接写 {@code setStatus(1)}，库里注释说"1 有效"、代码里只有一个魔数 1，
     * 两者靠人脑对齐。提成常量后，"写的是什么状态"在代码里可读，字典一致性闸门也不必再把 1 当成孤值候选。
     * 【注意】回补不靠改本字段表达，而是新增一条正数 changeNum 的流水 —— 所以不存在"已回滚"状态。
     */
    public static final int STATUS_VALID = 1;

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

    /** 状态：写入路径一律置 1（有效）。回补不靠改本字段表达，而是新增一条正数 changeNum 的流水 */
    private Integer status;

    private LocalDateTime createTime;
}

