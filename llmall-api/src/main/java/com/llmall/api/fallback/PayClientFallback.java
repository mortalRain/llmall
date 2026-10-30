package com.llmall.api.fallback;

import com.llmall.api.client.PayClient;
import com.llmall.api.dto.PayOrderStatusDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * 支付域 Feign 降级。
 *
 * 【注意】降级一律返回 {@link PayOrderStatusDTO#unknown(String)}：它只表达"我查不到"，
 * 不表达任何业务事实。把"未知"当成"未支付"会关掉用户已经付过钱的订单，
 * 所以调用方看到 determined=false 必须重试或告警，不得替支付域下结论。
 */
@Slf4j
public class PayClientFallback implements FallbackFactory<PayClient> {
    @Override
    public PayClient create(Throwable cause) {
        return new PayClient() {
            @Override
            public PayOrderStatusDTO closeUnpaid(com.llmall.api.dto.TradeOrderDTO order) {
                return PayOrderStatusDTO.unknown("支付服务不可用，未执行关闭");
            }
            @Override
            public PayOrderStatusDTO queryPayOrderByBizOrderNo(Long id) {
                log.error("远程调用PayClient的queryPayOrderByBizOrderNo失败，支付状态未知，bizOrderNo：{}", id, cause);
                // 关键：返回"未知"而不是 null / notFound。后两者都会被误读成"未支付"
                return PayOrderStatusDTO.unknown("支付服务不可用: " + cause.getClass().getSimpleName());
            }

            /**
             * 【注意】关闭支付单失败必须抛出异常，使交易域保留退款待办：支付单状态是钱的账本，
             * 静默成功会让交易域以为已经关闭，从而留下"订单已退款关闭、支付单仍待支付"的不一致。
             */
            @Override
            public void closePayOrder(Long bizOrderNo) {
                log.error("远程调用PayClient的closePayOrder失败，未关闭支付单，bizOrderNo：{}", bizOrderNo, cause);
                throw new com.llmall.common.exception.BizIllegalException("支付服务不可用，关闭操作未完成", cause);
            }
        };
    }
}
