package com.llmall.trade.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.llmall.api.dto.OrderDetailDTO;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "交易下单表单实体")
public class OrderFormDTO {
    @Schema(description = "收货地址id")
    private Long addressId;
    @Schema(description = "支付类型")
    private Integer paymentType;
    @Schema(description = "下单商品列表")
    private List<OrderDetailDTO> details;

    /**
     * 使用的优惠券（券实例 id，即 user_voucher.id），可不传。
     *
     * 为什么用"券实例 id"而不是"券模板 id"：模板是"满 50 减 10"这类规则，
     * 用户领到手的是实例，只有实例才能表达"这一张已经被谁用在哪笔订单上"。
     * 字段可空是为了向后兼容：老前端不传券时，下单行为与之前完全一致（全额下单）。
     *
     * 它同时也是**并发控制的载体**：下单时用这张券做条件核销（status 1→2 并绑定订单号），
     * 并发下单里只有一个请求能改到行，因此不存在"两笔订单用同一张券各抵一次"。
     */
    @Schema(description = "使用的优惠券id（券实例id，可空；服务端会校验归属/状态/有效期/门店）")
    private Long userVoucherId;
}
