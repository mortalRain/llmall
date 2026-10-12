package com.llmall.api.fallback;

import com.llmall.api.client.PayClient;
import com.llmall.api.dto.PayOrderStatusDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * 支付域 Feign 降级。
 *
 * <h3>这里最容易犯的错：把"降级"翻译成"业务结论"</h3>
 * 原实现查询失败时返回 null，而调用方（超时关单逻辑）把 null 理解为"没有支付单 = 未支付"，
 * 于是支付服务一抖动，已经付过钱的订单就被关掉、库存被退回 —— 故障被放大成资损。
 * 现在降级一律返回 {@link PayOrderStatusDTO#unknown(String)}：它只表达"我查不到"，
 * **不表达任何业务事实**，调用方看到 determined=false 就必须重试或告警，
 * 而不是替支付域下结论。
 */
@Slf4j
public class PayClientFallback implements FallbackFactory<PayClient> {
    @Override
    public PayClient create(Throwable cause) {
        return new PayClient() {
            @Override
            public PayOrderStatusDTO queryPayOrderByBizOrderNo(Long id) {
                log.error("远程调用PayClient的queryPayOrderByBizOrderNo失败，支付状态未知，bizOrderNo：{}", id, cause);
                // 关键：返回"未知"而不是 null / notFound。null 和 notFound 都会被误读成"未支付"
                return PayOrderStatusDTO.unknown("支付服务不可用: " + cause.getClass().getSimpleName());
            }

            /**
             * 关闭支付单的降级：只记录日志，不做任何"假装成功"的动作。
             * 支付单状态是钱的账本，这里静默成功会让交易域以为已经关闭，
             * 从而留下"订单已退款关闭、支付单仍待支付"的不一致却无人知晓。
             * 熔断场景下宁可让调用方看到失败日志（退款本身已落库，不回滚资金动作）。
             */
            @Override
            public void closePayOrder(Long bizOrderNo) {
                log.error("远程调用PayClient的closePayOrder失败，未关闭支付单，bizOrderNo：{}", bizOrderNo, cause);
            }
        };
    }
}