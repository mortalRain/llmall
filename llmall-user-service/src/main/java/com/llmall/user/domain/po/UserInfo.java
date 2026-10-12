package com.llmall.user.domain.po;

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
 * 用户详细资料（与 user 表 1:1）。
 *
 * 合并来源：点评 tb_user_info —— 积分层级属于"用户画像"而非"账号凭证"，
 * 因此不塞进 user 表，避免账号表越来越宽（这也是实战里常见的表设计取舍）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 id（与 user.id 一致，非自增，由业务写入） */
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private String city;

    private String introduce;

    /** 粉丝数 */
    private Integer fans;

    /** 关注数 */
    private Integer followee;

    /** 0 未知 1 男 2 女 */
    private Integer gender;

    private LocalDate birthday;

    /** 积分（与 promotion.points_flow 流水对账） */
    private Integer credits;

    /** 会员等级 1~9，0 表示未开通 */
    private Integer level;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
