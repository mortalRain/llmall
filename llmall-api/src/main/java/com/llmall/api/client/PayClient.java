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
     * <p>返回值刻意不是"支付单本体或 null"，而是显式的三态结果 {@link PayOrderStatusDTO}：
     * 调用方必须能区分"确认没有支付单"（确定的业务结论，可以关单）与
     * "状态未知"（查询降级，绝不能当成没支付）。用 null 表达"没支付"的设计，
     * 会在支付服务不可用时把"未知"翻译成"未支付"，从而关掉用户已经付过钱的订单。
     *
     * @param id 业务订单id
     * @return 三态支付状态（determined=false 时不得据此做任何业务决定）
     */
    @GetMapping("/pay-orders/biz/{id}")
    PayOrderStatusDTO queryPayOrderByBizOrderNo(@PathVariable("id") Long id);

    /**
     * 关闭支付单（交易订单退款后调用），把支付账本收敛到终态"已关闭"。
     *
     * 为什么订单退了还要管支付单：支付单是"钱的账本"。已退款订单对应的支付单如果停在
     * status=1（待支付），用户在"我的支付单"里还能看到一张待支付的单子并尝试支付；
     * 如果停在 status=3（支付成功），对账时看到的是"支付成功"与交易侧的"已退款关闭"矛盾，
     * 无法区分"用户付了钱"和"钱已经退回去了"。所以退款成功必须回写支付单。
     *
     * 可变更的源状态（0 未提交 / 1 待支付 / 3 支付成功 → 2 已关闭）与"哪些状态不允许被改"
     * 这条约束写在 llmall-pay-service 的条件更新里，因为支付单状态的权威在支付域。
     * 已经关闭的单子不会被再改一次，因此本接口重复调用是幂等的。
     */
    @PutMapping("/pay-orders/{bizOrderNo}/close")
    void closePayOrder(@PathVariable("bizOrderNo") Long bizOrderNo);
}