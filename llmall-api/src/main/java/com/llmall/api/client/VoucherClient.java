package com.llmall.api.client;

import com.llmall.api.dto.UserVoucherDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 营销域（llmall-promotion-service）券接口的跨服务客户端。
 *
 * 【注意】不加 fallbackFactory：券的"能不能用/锁没锁上"直接决定订单金额与用户资产，
 * 降级成 null/false 会让交易域把"券没锁上"当成"券可用"，属资损级错误；宁可让 Feign 抛出异常，
 * 由调用方翻译成"优惠券不可用"的业务提示。用户身份不由参数传入，而是由 DefaultFeignConfig
 * 从 ThreadLocal 原样透传，promotion 侧统一用 UserContext 做归属校验，客户端无法伪造代他人用券。
 */
@FeignClient("llmall-promotion-service")
public interface VoucherClient {

    /**
     * 查询"我的券"详情（券实例 id）。
     *
     * 【注意】下单前必须回查券的真实状态与面额，不能只信前端传来的 userVoucherId：
     * id 是可枚举的自增值，不校验归属就等于"知道 id 就能用别人的券抵自己的单"；
     * 归属校验在 promotion 侧完成（非本人持有会以 403 业务错误体返回）。
     */
    @GetMapping("/vouchers/my/{id}")
    UserVoucherDTO queryMyVoucher(@PathVariable("id") Long id);

    /**
     * 核销（锁定）券并把券绑定到订单号：状态 1 → 2。
     *
     * 【注意】只放在 /internal 路径上：该路径不在网关的路由表里，外部 HTTP 根本到不了；
     * 也不能改成在公开路径上加"内部凭证校验"就完事 —— 网关会给每一个认证通过的外部请求注入
     * 同一个内部凭证头，任何登录用户都能满足该校验，等于没校验，边界必须画在"外部到不了"上。
     * orderId 必传：营销域会据此回查订单（存在、属于本人、门店一致、状态允许，且
     * order.voucher_id 就是这张券），从根上堵掉"用户自己把券核销掉"。
     */
    @PostMapping("/internal/vouchers/my/{id}/use")
    Boolean useMyVoucher(@PathVariable("id") Long id, @RequestParam("orderId") Long orderId);

    /**
     * 退回券（状态 2 → 1，幂等），仅供订单取消/退款链路调用。
     *
     * orderId 必传，且同样会在营销域回查订单：防止 A 订单取消时把 B 订单用掉的券退回来。
     */
    @PutMapping("/internal/vouchers/my/{id}/refund")
    Boolean refundMyVoucher(@PathVariable("id") Long id, @RequestParam("orderId") Long orderId);
}
