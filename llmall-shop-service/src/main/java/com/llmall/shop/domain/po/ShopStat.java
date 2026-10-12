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
 * UV 用 Redis HyperLogLog 估算（亿级数据只需 12KB），每日落库一份供报表查询。
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

    /** 统计日期 */
    private LocalDate statDate;

    /** 访问次数 */
    private Long pv;

    /** 访问人数（HyperLogLog 估算值） */
    private Long uv;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
