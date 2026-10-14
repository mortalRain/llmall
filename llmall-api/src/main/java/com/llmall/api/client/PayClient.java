package com.llmall.api.client;
import com.llmall.api.dto.PayOrderStatusDTO;
import com.llmall.api.fallback.PayClientFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@FeignClient(value = "llmall-pay-service", fallbackFactory = PayClientFallback.class)
public interface PayClient {
    /**
     * 根据交易订单id查询支付状态。
     *
     * 【注意】返回的是三态结果 {@link PayOrderStatusDTO}，不是"支付单或 null"：调用方必须区分
     * "确认没有支付单"（可关单）与"状态未知"（查询降级，绝不能当成没支付）。
     *
     * @param id 业务订单id
     * @return 三态支付状态（determined=false 时不得据此做任何业务决定）
     */
    @GetMapping("/pay-orders/biz/{id}")
    PayOrderStatusDTO queryPayOrderByBizOrderNo(@PathVariable("id") Long id);

    /**
     * 关闭支付单（交易订单退款后调用），把支付账本收敛到终态"已关闭"。
     *
     * 【注意】退款成功必须回写支付单：支付单停在 1 待支付或 3 支付成功，都会与交易侧的
     * "已退款关闭"相矛盾，对账时无法区分钱是否已退回。可变更的源状态
     * （0 未提交 / 1 待支付 / 3 支付成功 → 2 已关闭）由 llmall-pay-service 的条件更新判定；
     * 已关闭的单子不会再被改，因此重复调用幂等。
     */
    @PutMapping("/pay-orders/{bizOrderNo}/close")
    void closePayOrder(@PathVariable("bizOrderNo") Long bizOrderNo);
}