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
 * 积分、层级属于用户画像而非账号凭证，因此不塞进 user 表，避免账号表越来越宽。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会员等级「未开通」，也是新建资料时的初值。
     *
     * 【为什么要有这个名字】补建资料时若直接写 {@code setLevel(0)}，库里注释说"0 未开通"、代码里只有一个魔数 0，两者只能靠人脑对齐；提成常量后"写的是什么等级"在代码里可读。
     * 【注意】1~9 的等级体系目前没有任何写路径（等级恒为未开通），因此这里只为 0 声明常量。
     */
    public static final int LEVEL_NOT_OPENED = 0;

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

    /** 积分（与 `llmall-user`.`points_flow` 流水对账；该表在用户库，不在营销库） */
    private Integer credits;

    /** 会员等级 1~9，0 表示未开通 */
    private Integer level;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

