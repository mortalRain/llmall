package com.llmall.promotion.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀券配置，对应 `llmall-promotion`.`seckill_voucher`。
 *
 * 【注意】主键为 voucher_id（与 voucher 是一对一扩展表），故 {@code IdType.INPUT}，值由 voucher 侧决定。
 * "这张券是不是秒杀券"以本表有没有记录为判据：voucher.type=1 只是运营打的标签，
 * 真正的库存与时段配置只存在于这张表里，有记录才有"抢"的语义。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("seckill_voucher")
public class SeckillVoucher implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "voucher_id", type = IdType.INPUT)
    private Long voucherId;

    /** 库存：Redis 预扣减在前、DB 条件扣减在后，以 DB 为最终账本 */
    private Integer stock;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
