package com.llmall.api.client;

import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.common.constant.AuthConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient("llmall-trade-service")
public interface TradeClient {
    /**
     * 【内部接口】标记订单支付成功。
     *
     * 【注意】这是钱的结论，不能由用户自身触发。登录态只能证明"你是谁"，用户请求同样带着
     * 网关注入的 x-internal-token，所以这里要求只有服务调用方持有的 x-service-token
     * （网关对客户端自带的该头只剥离、不注入），其值须与服务端配置项
     * {@code llmall.internal.service-token} 一致。
     */
    @PutMapping("/orders/{orderId}")
    void markOrderPaySuccess(@PathVariable("orderId") Long orderId,
                             @RequestHeader(AuthConstants.HEADER_SERVICE_TOKEN) String serviceToken);

    /**
     * 查询交易订单（支付前状态校验用）。
     *
     * 【注意】必须在扣款前确认订单仍为"待付款"：订单超时关单与用户支付之间存在竞态，
     * 只校验支付单自身状态会出现"订单已关闭、钱却扣了"的资损。
     */
    @GetMapping("/orders/{orderId}")
    TradeOrderDTO queryOrderById(@PathVariable("orderId") Long orderId);
}
