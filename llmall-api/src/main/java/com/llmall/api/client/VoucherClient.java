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
 * 设计取舍：
 *  1. 不加 fallbackFactory：券的"能不能用/锁没锁上"直接决定订单金额与用户资产，
 *     降级成 null/false 会让交易域把"券没锁上"当成"券可用"，属于资损级错误。
 *     宁可让 Feign 直接抛异常，由调用方翻译成"优惠券不可用"的业务提示。
 *  2. 身份不通过参数传：网关校验 JWT 后写入 user-info 头，llmall-common 的拦截器把它
 *     放进 ThreadLocal，DefaultFeignConfig 的拦截器再原样透传到下游 ——
 *     promotion 侧统一用 UserContext 做归属校验，客户端无法伪造"帮别人用券"。
 *     唯一的例外是 MQ 线程里的补偿调用（取消订单退券），那时交易域会**显式**以
 *     订单归属人的身份发起调用（见 OrderServiceImpl#refundVoucherQuietly）。
 */
@FeignClient("llmall-promotion-service")
public interface VoucherClient {

    /**
     * 查询"我的券"详情（券实例 id）。
     *
     * 下单前必须回查一次券的真实状态与面额，不能只信前端传来的 userVoucherId：
     * id 是可枚举的自增值，不校验归属就等于"知道 id 就能用别人的券抵自己的单"。
     * 归属校验在 promotion 侧完成（非本人持有会以 403 业务错误体返回）。
     */
    @GetMapping("/vouchers/my/{id}")
    UserVoucherDTO queryMyVoucher(@PathVariable("id") Long id);

    /**
     * 核销（锁定）券并把券绑定到订单号：状态 1 → 2。
     *
     * <h3>为什么走 /internal 前缀</h3>
     * 券核销是**钱的结论**（券一旦置为已使用，就等于平台对这笔订单让利了），
     * 绝不能让用户自助触发。所以核销接口只放在内部路径上：
     *   ① 内部路径不在网关的路由表里，外部 HTTP 根本到不了；
     *   ② 入口再校验一次 {@code x-internal-token}（本客户端发出的调用由
     *      DefaultFeignConfig 自动带上该头），双重把关。
     * 注意：**不能**只在原来那个公开路径上加"内部凭证校验"就完事 ——
     * 网关会给每一个"认证通过的外部请求"注入同一个内部凭证头，
     * 于是任何登录用户都能通过网关满足这个校验，等于没校验。
     * 边界必须画在"这条路径外部到不了"上，而不是画在"这个头看起来对"上。
     *
     * <h3>orderId 必传</h3>
     * 营销域会用它在交易域**回查订单**（存在、属于本人、门店一致、状态允许、
     * 且 order.voucher_id 就是这张券），从根上堵掉"用户自己把券核销掉"。
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
