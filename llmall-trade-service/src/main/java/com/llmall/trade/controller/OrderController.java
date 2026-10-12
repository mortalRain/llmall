package com.llmall.trade.controller;

import io.swagger.v3.oas.annotations.enums.ParameterIn;

import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.domain.PageQuery;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.utils.BeanUtils;

import com.llmall.trade.domain.dto.OrderFormDTO;
import com.llmall.trade.domain.dto.OrderVerifyDTO;
import com.llmall.trade.domain.vo.OrderVO;
import com.llmall.trade.service.IOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "订单管理接口")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {
    private final IOrderService orderService;

    /**
     * 服务间调用凭证（llmall.internal.service-token，无默认值：**必须显式配置**）。
     * 不设默认值是有意的 —— 有默认值就意味着"忘了配"时这个内部接口悄悄对外开放，
     * 而它的默认值一旦被写进代码，就等于公开的密码。
     *
     * <p>注意它**不是** llmall.internal.token：后者是"请求经过了网关"的凭证，
     * 登录用户经网关发来的请求天然带着它，用它判"内部调用"等于没判（实测踩过）。
     */
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    @Operation(summary = "我的订单列表（按当前登录用户，可按状态筛选，含商品明细）")
    @GetMapping
    public PageDTO<OrderVO> queryMyOrders(PageQuery query,
                                          @RequestParam(value = "status", required = false) Integer status) {
        return orderService.queryMyOrders(query, status);
    }

    @Operation(summary = "根据id查询订单（含商品明细）")
    @GetMapping("{id}")
    public OrderVO queryOrderById(@Param ("订单id")@PathVariable("id") Long orderId) {
        return orderService.queryOrderDetail(orderId);
    }

    @Operation(summary = "创建订单")
    @PostMapping
    public Long createOrder(@RequestBody OrderFormDTO orderFormDTO){
        return orderService.createOrder(orderFormDTO);
    }

    @Operation(summary = "用户取消订单（仅未支付可取消，自动回滚库存）")
    @PutMapping("/{orderId}/cancel")
    public void cancelOrder(@PathVariable("orderId") Long orderId) {
        orderService.cancelMyOrder(orderId);
    }

    /**
     * 到店核销（门店侧调用，需登录）。
     * 把"已付款待使用"（status=2）的订单置为"已核销"（status=7），是订单终态 7 的唯一入口。
     */
    @Operation(summary = "到店核销订单（凭核销码核销，仅已付款未使用的订单可核销）")
    @PostMapping("/verify")
    public Long verifyOrder(@RequestBody @Validated OrderVerifyDTO verifyDTO) {
        return orderService.verifyOrder(verifyDTO);
    }

    /**
     * 已支付订单退款（用户发起，需登录）。
     * 退余额 + 回滚库存 + 退回优惠券 + 关闭支付单；已核销、未支付的订单一律拒绝。
     */
    @Operation(summary = "已支付订单退款（仅“已付款未核销”可退，退款后订单状态变为 5 已关闭）")
    @Parameter(name = "orderId", description = "订单id", in = ParameterIn.PATH, required = true)
    @PostMapping("/{orderId}/refund")
    public void refundOrder(@PathVariable("orderId") Long orderId) {
        orderService.refundMyOrder(orderId);
    }

    /**
     * 【内部接口】标记订单已支付 —— 由支付域（MQ 的 pay.success 或内部 Feign）触发，
     * 不是用户接口。
     *
     * <p>为什么不能只靠"已登录"：登录态只回答"你是谁"，回答不了"你有没有付钱"。
     * 只要知道订单号就能把自己的订单标成已支付且不花钱，是最直接的资损路径 ——
     * 所以除了网关的登录校验，这里再要求调用方携带**只在服务间流通**的凭证头
     * {@code x-service-token}（与配置项 llmall.internal.service-token 比对）。
     *
     * <p>为什么不用 x-internal-token：那个头由网关在认证通过后注入，
     * 任何登录用户的请求都带着它，拿它判"内部调用"会把用户的请求一起放进来。
     * 而 x-service-token 网关**只剥离、不注入**（见 AuthGlobalFilter 第 0 步），
     * 所以外部请求无论怎么试都拿不到它。
     *
     * <p>为什么凭证校验放在控制器入口：MQ 监听器（PayStatusListener）是**直接调 service 方法**的，
     * 不经过 HTTP，所以把边界画在 HTTP 入口既能挡掉外部调用，又完全不破坏支付回调链路；
     * 反过来，如果把校验下沉到 service 里，反而会把 MQ 回调一起挡掉。
     */
    @Operation(summary = "标记订单已支付（内部接口，需服务间凭证）")
    @Parameter(name = "orderId", description = "订单id", in = ParameterIn.PATH)
    @PutMapping("/{orderId}")
    public void markOrderPaySuccess(@PathVariable("orderId") Long orderId,
                                    @RequestHeader(value = AuthConstants.HEADER_SERVICE_TOKEN, required = false)
                                    String requestToken) {
        if (!StringUtils.hasText(serviceToken)
                || !StringUtils.hasText(requestToken)
                || !serviceToken.equals(requestToken)) {
            // 配置缺失时同样拒绝（fail-closed）：不能因为"运维忘了配"而让内部接口变成公开接口
            throw new ForbiddenException("内部接口，禁止外部调用");
        }
        orderService.markOrderPaySuccess(orderId);
    }
}
