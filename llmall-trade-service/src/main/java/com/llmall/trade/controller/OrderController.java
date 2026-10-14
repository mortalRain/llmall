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

    /** 服务间调用凭证（llmall.internal.service-token）：故意不给默认值，配置缺失时必须让校验失败 */
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

    /** 到店核销（门店侧调用，需登录）：把"已付款待使用"（status=2）的订单置为"已核销"（status=7），是订单终态 7 的唯一入口 */
    @Operation(summary = "到店核销订单（凭核销码核销，仅已付款未使用的订单可核销）")
    @PostMapping("/verify")
    public Long verifyOrder(@RequestBody @Validated OrderVerifyDTO verifyDTO) {
        return orderService.verifyOrder(verifyDTO);
    }

    /** 已支付订单退款（用户发起，需登录）：退余额 + 回滚库存 + 退回优惠券 + 关闭支付单；已核销、未支付的订单一律拒绝 */
    @Operation(summary = "已支付订单退款（仅“已付款未核销”可退，退款后订单状态变为 5 已关闭）")
    @Parameter(name = "orderId", description = "订单id", in = ParameterIn.PATH, required = true)
    @PostMapping("/{orderId}/refund")
    public void refundOrder(@PathVariable("orderId") Long orderId) {
        orderService.refundMyOrder(orderId);
    }

    /**
     * 【内部接口】标记订单已支付：由支付域（MQ 的 pay.success 或内部 Feign）触发，不是用户接口。
     *
     * 【注意】不能只靠"已登录"：登录态只回答"你是谁"，回答不了"你有没有付钱" —— 只要知道订单号就能把自己的
     * 订单标成已支付且不花钱，是最直接的资损路径。故额外要求调用方携带只在服务间流通的凭证头
     * {@code x-service-token}（与配置项 llmall.internal.service-token 比对）：网关对该头只剥离、不注入，
     * 外部请求拿不到它；而 x-internal-token 由网关在认证通过后注入，任何登录用户的请求都带着它，
     * 拿它判"内部调用"等于没判。校验放在控制器入口而非 service：MQ 监听器（PayStatusListener）直接调
     * service 方法、不经过 HTTP，下沉到 service 会把支付回调链路一起挡掉。
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
