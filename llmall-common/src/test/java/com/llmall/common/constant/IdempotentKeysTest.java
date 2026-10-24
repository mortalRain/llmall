package com.llmall.common.constant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等键形态的单元测试。
 *
 * 【为什么值得测】扣款键与退款键一旦变成同一个值，失败补偿就会把另一笔扣款退掉；
 * 键又跨服务使用，格式漂移只能在联调时暴露，靠单测把形态固定下来成本最低。
 */
class IdempotentKeysTest {

    @Test
    void payDeductIsDeterministic() {
        String first = IdempotentKeys.payDeduct(1001L, 25600);
        String second = IdempotentKeys.payDeduct(1001L, 25600);
        assertThat(first).isEqualTo(second).isEqualTo("PAY:1001:25600");
    }

    @Test
    void refundKeyDiffersFromDeductKey() {
        String deduct = IdempotentKeys.payDeduct(1001L, 25600);
        String refund = IdempotentKeys.payFailRefund(deduct);
        assertThat(refund).isEqualTo("PAYFAIL:" + deduct);
        assertThat(refund).isNotEqualTo(deduct);
    }

    @Test
    void amountParticipatesInDeductKey() {
        assertThat(IdempotentKeys.payDeduct(1L, 100))
                .isNotEqualTo(IdempotentKeys.payDeduct(1L, 200));
    }

    @Test
    void voucherBuyKeyIsPerUserAndVoucher() {
        assertThat(IdempotentKeys.voucherBuy(9L, 7L)).isEqualTo("VOUCHER_BUY_9_7");
        assertThat(IdempotentKeys.voucherBuy(9L, 8L))
                .isNotEqualTo(IdempotentKeys.voucherBuy(9L, 7L));
    }

    @Test
    void voucherRefundIsUniquePerAttempt() {
        String first = IdempotentKeys.voucherRefund(9L, 7L, "attempt-a");
        String second = IdempotentKeys.voucherRefund(9L, 7L, "attempt-b");
        assertThat(first).isNotEqualTo(second);
        assertThat(first).startsWith("VOUCHER_REFUND_9_7_");
    }

    @Test
    void seckillRefundDiffersFromPayKey() {
        String token = "token-1";
        assertThat(IdempotentKeys.seckillRefund(token))
                .isNotEqualTo(IdempotentKeys.voucherSeckillPay(token));
    }

    @Test
    void orderRefundIsPerOrder() {
        assertThat(IdempotentKeys.orderRefund(5L)).isEqualTo("ORDER_REFUND:5");
    }
}
