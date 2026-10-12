package com.llmall.api.client;

import com.llmall.api.dto.MoneyDeductDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient("llmall-user-service")
public interface UserClient {

    /**
     * 扣减余额。
     *
     * <p>【为什么从 query 改成请求体】原签名是两个 {@code @RequestParam("pw"/"amount")}，
     * Feign 会把它们拼进 URL，于是支付密码出现在请求行里 —— 而请求行会被写进
     * llmall-pay-service 的应用日志、access log、链路追踪，实测日志里就能 grep 到 {@code pw=123456}。
     * 密码是必须走请求体的凭据：body 不进 access log，也能随日志级别整体关闭。
     * 这里额外带上 bizNo（订单号）便于对账追溯。
     */
    @PostMapping("/users/money/deduct")
    void deductMoney(@RequestBody MoneyDeductDTO deductDTO);

    /**
     * 退款：把余额加回指定用户（内部接口，供交易域的退款闭环调用）。
     *
     * 为什么把 userId 显式作为参数传，而不是像 deductMoney 那样从 UserContext 取：
     * 退款可能由**非用户本人**的路径触发（超时关单/人工退款/后台任务），
     * 那些线程里没有登录上下文；而这里的 userId 取自交易域自己库里的订单归属人，
     * 不是前端传参，因此不存在"指定退款给谁"的越权面 —— 权限边界在交易域
     * （只允许订单归属人发起），不在这个内部接口上。
     */
    @PostMapping("/users/money/refund")
    void refundMoney(@RequestParam("userId") Long userId, @RequestParam("amount") Integer amount);

    /**
     * 对账用：查询某订单的积分是否已发放。
     * 由交易域的对账任务调用 —— 只有下游能回答"我到底处理了没有"，
     * 因此对账必须有一个可查询的"已处理"判据（这里是积分流水的唯一键）。
     */
    @GetMapping("/points/orders/{orderId}/applied")
    boolean isPointsApplied(@PathVariable("orderId") Long orderId);
}
