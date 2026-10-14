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
 * (biz_type, biz_id, type) 唯一索引是幂等闸门，也是对账判据。
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
