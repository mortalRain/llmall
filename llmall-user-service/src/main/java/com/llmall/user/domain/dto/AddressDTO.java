package com.llmall.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 收货地址实体。
 *
 * <p>为什么手机号要用正则而不是只判空：地址是下单页唯一的联系方式来源，
 * 一个手误的号码（10 位、带空格、写成座机）会在配送环节才暴露，而那时已经无法联系用户；
 * 正则把这类错误挡在表单提交的第一时间。
 *
 * <p>长度上限对着 information_schema 里的真实列宽写（province/city/town varchar(10)、
 * mobile/street/contact varchar(255)）：MySQL 严格模式下超长是**报错**而不是截断，
 * 不写上限就会把"用户输入太长"表现成 500。
 */
@Data
@Schema(description = "收货地址实体")
public class AddressDTO {
    @Schema(description = "id")
    private Long id;
    @Schema(description = "省")
    @Size(max = 10, message = "省份过长（最多10字）")
    private String province;
    @Schema(description = "市")
    @Size(max = 10, message = "城市过长（最多10字）")
    private String city;
    @Schema(description = "县/区")
    @Size(max = 10, message = "区县过长（最多10字）")
    private String town;
    @Schema(description = "手机")
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;
    @Schema(description = "详细地址")
    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址过长（最多255字）")
    private String street;
    @Schema(description = "联系人")
    @NotBlank(message = "联系人不能为空")
    @Size(max = 255, message = "联系人过长（最多255字）")
    private String contact;
    @Schema(description = "是否是默认 1默认 0否")
    private Integer isDefault;
    @Schema(description = "备注")
    @Size(max = 255, message = "备注过长（最多255字）")
    private String notes;
}
