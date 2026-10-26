package com.llmall.api.client;

import com.llmall.api.dto.MoneyDeductDTO;
import com.llmall.api.dto.UserProfileDTO;
import com.llmall.api.dto.UserProfileQueryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient("llmall-user-service")
public interface UserClient {

    /**
     * 批量查询用户展示资料（内部接口，需服务间凭证 x-service-token）。
     *
     * 【为什么批量】评价列表一页最多十几条，逐条查用户资料就是 N+1；本接口让内容域固定一次调用
     * 取回整页作者的昵称/头像。只返回昵称/头像，不返回手机号/余额等账户字段。
     *
     * 【降级】用户服务抖动时应由调用方按"昵称为空"处理（回退同城用户{id}），
     * 不能让评价列表整体 500；因此这里不配置会改变资金语义的 fallback。
     */
    @PostMapping("/internal/users/profiles")
    List<UserProfileDTO> queryProfiles(@RequestBody UserProfileQueryDTO query);

    /** 扣减余额。支付密码只能随请求体传递，不得放进 URL query。 */
    @PostMapping("/users/money/deduct")
    void deductMoney(@RequestBody MoneyDeductDTO deductDTO);

    /**
     * 退款：把余额加回指定用户（内部接口，供交易域/营销域/支付域的退款闭环调用）。
     *
     * 【注意】userId 由调用方显式传入，不取登录上下文：退款可能由超时关单/人工退款/后台任务触发。
     * 该值取自交易域订单归属人而非前端传参，越权边界在交易域而非本接口。
     * bizNo 为必填幂等键（下游 fail-closed），同一笔退款重复调用只入账一次；口径：
     * ORDER_REFUND:订单id、PAYFAIL:扣款业务号、SECKILL_REFUND:token。
     */
    @PostMapping("/users/money/refund")
    void refundMoney(@RequestParam("userId") Long userId, @RequestParam("amount") Integer amount,
                     @RequestParam("bizNo") String bizNo);

    /** 对账用：查询某订单的积分是否已发放（积分流水唯一键即"已处理"判据）。 */
    @GetMapping("/points/orders/{orderId}/applied")
    boolean isPointsApplied(@PathVariable("orderId") Long orderId);
}
