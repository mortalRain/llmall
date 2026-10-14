package com.llmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户收货地址。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("address")
public class Address implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String province;

    private String city;

    /**
     * 县/区（库列名为 town）
     */
    private String town;

    private String mobile;

    /**
     * 详细地址（库列名为 street）
     */
    private String street;

    private String contact;

    /**
     * 是否默认地址：1 默认 0 否
     */
    private Integer isDefault;

    private String notes;


}
