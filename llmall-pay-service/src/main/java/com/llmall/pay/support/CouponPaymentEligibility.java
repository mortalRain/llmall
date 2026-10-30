package com.llmall.pay.support;

import com.llmall.api.client.VoucherClient;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.api.dto.UserVoucherDTO;
import com.llmall.common.exception.BizIllegalException;

import java.util.Objects;

/** 已抵扣订单必须先确认券绑定；只读检查，不负责锁券或修改已确定的实付金额。 */
public final class CouponPaymentEligibility {
    private CouponPaymentEligibility() { }

    public static void requireBound(TradeOrderDTO order, VoucherClient vouchers) {
        if (order.getVoucherId() == null) {
            return;
        }
        UserVoucherDTO voucher;
        try {
            voucher = vouchers.queryMyVoucher(order.getVoucherId());
        } catch (RuntimeException failure) {
            throw new BizIllegalException("优惠券绑定暂无法确认，请稍后重试", failure);
        }
        if (voucher == null || !Objects.equals(order.getVoucherId(), voucher.getId())
                || !Integer.valueOf(2).equals(voucher.getStatus()) || order.getId() == null
                || !Objects.equals(order.getId(), voucher.getOrderId())
                || (voucher.getShopId() != null && !Objects.equals(order.getShopId(), voucher.getShopId()))) {
            throw new BizIllegalException("优惠券未绑定当前订单，暂不能支付");
        }
    }
}
