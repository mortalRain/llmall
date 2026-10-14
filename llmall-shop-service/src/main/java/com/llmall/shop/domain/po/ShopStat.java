package com.llmall.shop.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 门店访问统计（按天）。
 *
 * 数据由 {@code ShopStatRollupTask} 每日从 Redis 落到本表：UV 取 HyperLogLog 估算值、
 * PV 取当日计数器，两者的 key 都按天新建（前缀 {@code uv:shop:} / {@code pv:shop:}）。
 *
 * 【注意】本表是"历史留档"而非实时口径：Redis 侧为控制内存只保留 2 天（{@code llmall.shop.cache.uv-ttl-days}），
 * 落库之后才能回答"上周某天有多少人访问过这家门店"。实时值请查 Redis，不要查库。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("shop_stat")
public class ShopStat implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 统计日期（按天，与 shop_id 组成唯一键，保证落库任务可重复执行） */
    private LocalDate statDate;

    /** 当日访问次数 */
    private Long pv;

    /** 当日访问人数（HyperLogLog 估算值，标准误差 0.81%） */
    private Long uv;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
