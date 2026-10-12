package com.llmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分流水。
 *
 * 与 user_info.credits 同库同事务，保证"加积分 + 记流水"要么都成功、要么都回滚。
 * (biz_type, biz_id, type) 唯一索引同时承担：
 *  ① 幂等闸门（同一订单只加一次积分）；② 对账判据（交易域问"这笔订单积分发了吗"就查它）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("points_flow")
public class PointsFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 积分变动值（正数获得，负数消耗） */
    private Integer points;

    /** 1 下单获得 2 评价获得 3 签到 4 消耗 */
    private Integer type;

    private String bizType;

    private String bizId;

    private String remark;

    private LocalDateTime createTime;
}
