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
 * 用户持有的券（券实例），对应 `llmall-promotion`.`user_voucher`。
 *
 * 【注意】唯一键 uk_user_voucher(user_id, voucher_id) 是"一人一张"的数据库兜底闸门：
 * 普通领券与秒杀落库最终都在这里插行，重复请求必然撞唯一键，业务层再把
 * {@code DuplicateKeyException} 翻译成友好提示。正确性靠唯一约束而不是"先查再插" —— 并发下先查后插必有窗口。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_voucher")
public class UserVoucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 未使用 */
    public static final int STATUS_UNUSED = 1;
    /** 已使用（已核销） */
    public static final int STATUS_USED = 2;
    /** 已过期 */
    public static final int STATUS_EXPIRED = 3;

    /** 券实例 id：对外暴露的"我的券 id"，核销/退回都用它 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long voucherId;

    /** 冗余门店 id：核销/按门店统计时不必回表查 voucher */
    private Long shopId;

    /** 关联订单 id：券类订单核销时回填 */
    private Long orderId;

    /** 1 未使用 2 已使用 3 已过期 */
    private Integer status;

    private LocalDateTime receiveTime;

    private LocalDateTime useTime;

    /** 过期时间（领取时按券规则的有效天数算出并落库，避免每次查询都解析 JSON） */
    private LocalDateTime expireTime;

    private LocalDateTime updateTime;
}
