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
 * 主键就是 voucher_id（一对一扩展表），所以 {@code IdType.INPUT}：值由 voucher 侧决定，不能自增。
 * "这张券是不是秒杀券"以**本表有没有记录**为判据，而不是 voucher.type：
 * type=1 只是运营打的标签，真正的库存与时段配置只存在于这张表里，
 * 有记录才有"抢"的语义（本项目演示数据里 8001/8004 的 type 就是 0，但确实配了秒杀）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("seckill_voucher")
public class SeckillVoucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 券 id（与 voucher.id 一对一） */
    @TableId(value = "voucher_id", type = IdType.INPUT)
    private Long voucherId;

    /** 库存：Redis 预扣减在前，DB 条件扣减在后，DB 是最终账本 */
    private Integer stock;

    /** 秒杀开始时间 */
    private LocalDateTime beginTime;

    /** 秒杀结束时间 */
    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
