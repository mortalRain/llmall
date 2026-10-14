package com.llmall.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单物流信息：收件人地址与物流公司/单号，与订单表一对一。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("order_logistics")
public class OrderLogistics implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单id，与订单表一对一 */
    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;

    private String logisticsNumber;

    private String logisticsCompany;

    /** 收件人 */
    private String contact;

    /** 收件人手机号码 */
    private String mobile;

    private String province;

    private String city;

    /** 区 */
    private String town;

    private String street;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}
