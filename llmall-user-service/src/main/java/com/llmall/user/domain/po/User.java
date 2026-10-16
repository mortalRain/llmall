package com.llmall.user.domain.po;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.llmall.user.enums.UserStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户账号：登录口令与支付口令、手机号、使用状态与余额，对应 user 表。
 *
 * 【注意】本类有两个最容易被改错的取值口径：
 * 1. status 使用 UserStatus 枚举（0 冻结、1 正常），写入枚举之外的整数会让状态判断静默失效；
 * 2. balance 的单位是分，按元赋值会造成 100 倍差错。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String username;

    private String nickName;

    private String icon;

    /**
     * 登录口令（BCrypt 哈希，非明文）：只用于"用户名 + 密码"登录，与支付口令分列存放。
     *
     * 【注意】禁止把本列用于余额支付校验：两列一旦混用，改一种口令就会顺带改变另一种口令的能力。
     */
    private String password;

    /**
     * 支付口令（BCrypt 哈希，非明文），仅用于余额支付校验（llmall-pay-service → /users/money/deduct）。
     *
     * 【注意】与登录口令分离：登录口令须可随机化（不可预期），支付口令才需要用户可预期。
     * 本列由 sql/merged/60-user-pay-password.sql 添加：该脚本的 ADD COLUMN 默认值是明文占位，
     * 并由脚本内的 UPDATE 统一改写为 BCrypt 哈希 —— 两步必须都执行，
     * 否则存量账号的余额支付会因"明文与哈希比对不上"而不可用。
     *
     * 【安全约束】任何写入本字段的路径都必须先经 PasswordEncoder 编码；
     * 直接写明文会让 matches() 永远失败，且明文口令一旦泄露即可直接使用。
     */
    private String payPassword;

    /** 手机号：验证码登录的账号标识，唯一键；自动注册的用户名由本列派生 */
    private String phone;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 使用状态：0 冻结、1 正常，取值必须与 UserStatus 枚举逐值一致。
     *
     * 【注意】写入枚举中不存在的值（例如按"1 正常 2 冻结"把 2 当冻结）会让枚举反查得到 null，
     * 冻结会静默失效：请求不再被拒绝，账号状态判断全部落空。
     */
    private UserStatus status;

    /**
     * 账户余额（单位：分）
     */
    private Integer balance;


}
