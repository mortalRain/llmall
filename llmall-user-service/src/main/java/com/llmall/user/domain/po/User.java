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
 * 用户表（账号、密码、余额）。
 *
 * @author 同城生活
 * @since 2023-05-05
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
     * 密码（加密存储）。
     *
     * 【注意】余额支付的"支付密码"与登录密码是同一列：deductMoney 用本列的哈希校验支付密码。
     */
    private String password;

    private String phone;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 使用状态：0 冻结 1 正常，取值必须与 UserStatus 枚举一致。
     *
     * 【注意】写入枚举中不存在的值（例如按"1 正常 2 冻结"把 2 当冻结）会让枚举反查得到 null，
     * 冻结会静默失效。
     */
    private UserStatus status;

    /**
     * 账户余额（单位：分）
     */
    private Integer balance;


}
