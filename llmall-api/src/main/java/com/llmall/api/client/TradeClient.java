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
     * <p>为什么需要显式传内部凭证：这个接口在网关层只要求"已登录"，而它做的事
     * （把订单改成已支付）是**钱的结论**，绝不能由用户自己触发 —— 否则任何人只要
     * 知道订单号，就能不付钱把自己的订单标成已支付。登录态只能证明"你是谁"，
     * "经过了网关"也只能证明"请求是用户发的"（用户请求同样带着网关注入的 x-internal-token），
     * 所以这里用**只有服务调用方持有**的 x-service-token：网关对客户端自带的该头只剥离、不注入。
     *
     * <p>调用方必须传入与 llmall-trade-service 配置项 {@code llmall.internal.service-token} 相同的值
     * （本地演示值：llmall-service-demo-token）。生产环境请把该值放进配置中心/密钥管理，
     * 并配合内网隔离。
     */
    @PutMapping("/orders/{orderId}")
    void markOrderPaySuccess(@PathVariable("orderId") Long orderId,
                             @RequestHeader(AuthConstants.HEADER_SERVICE_TOKEN) String serviceToken);

    /**
     * 查询交易订单（支付前状态校验用）。
     *
     * 支付是"钱"的操作，必须在扣款前确认交易订单仍是"待付款"：
     * 订单超时关单与用户支付之间天然存在竞态，只校验支付单自身状态是不够的 ——
     * 会出现"订单已关闭、钱却扣了"的资损场景（本项目实测踩过）。
     */
    @GetMapping("/orders/{orderId}")
    TradeOrderDTO queryOrderById(@PathVariable("orderId") Long orderId);
}
