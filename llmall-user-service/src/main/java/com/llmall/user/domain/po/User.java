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
 * <p>
 * 用户表
 * </p>
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

    /**
     * 用户名
     */
    private String username;

    /**
     * 昵称（合并自点评 tb_user.nick_name）
     */
    private String nickName;

    /**
     * 头像（合并自点评 tb_user.icon）
     */
    private String icon;

    /**
     * 密码，加密存储
     */
    private String password;

    /**
     * 注册手机号
     */
    private String phone;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 使用状态（0 冻结 1 正常，见 UserStatus）
     *
     * <p>注意口径：与枚举保持一致是 0/1；历史上这里误写成"1正常 2冻结"，
     * 若按旧注释写 status=2，枚举反查会得到 null，冻结会**静默失效**。
     */
    private UserStatus status;

    /**
     * 账户余额
     */
    private Integer balance;


}
