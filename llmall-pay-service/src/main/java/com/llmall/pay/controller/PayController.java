package com.llmall.pay.controller;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import com.llmall.api.dto.PayOrderStatusDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.UserContext;
import com.llmall.pay.domain.dto.PayApplyDTO;
import com.llmall.pay.domain.dto.PayOrderFormDTO;
import com.llmall.pay.domain.po.PayOrder;
import com.llmall.pay.domain.vo.PayOrderVO;
import com.llmall.pay.enums.PayType;
import com.llmall.pay.service.IPayOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Tag(name = "支付相关接口")
@RestController
@RequestMapping("pay-orders")
@RequiredArgsConstructor
public class PayController {

    private final IPayOrderService payOrderService;
    @org.springframework.beans.factory.annotation.Value("${llmall.internal.service-token:}")
    private String serviceToken;

    @Operation(summary = "查询支付单")
    @GetMapping
    public List<PayOrderVO> queryPayOrders(){
        // 只返回自己的支付单：支付单带金额与业务订单号，不按 userId 过滤就是越权读。
        // 按创建时间倒序，与前端"最近支付记录在前"的展示顺序一致。
        Long userId = UserContext.getUser();
        if (userId == null) {
            // 【注意】未登录必须抛鉴权异常，返回空列表会让前端把鉴权问题显示成"暂无记录"。
            throw new UnauthorizedException("未登录");
        }
        List<PayOrder> orders = payOrderService.lambdaQuery()
                .eq(PayOrder::getBizUserId, userId)
                .orderByDesc(PayOrder::getCreateTime)
                .list();
        return BeanUtils.copyList(orders, PayOrderVO.class);
    }

    @Operation(summary = "生成支付单")
    @PostMapping
    // 【注意】@Valid 不能少：PayApplyDTO 上的 @NotNull/@Min 不加它就不生效，入参边界必须在入口兜住。
    public String applyPayOrder(@Valid @RequestBody PayApplyDTO applyDTO){
        if(!PayType.BALANCE.equalsValue(applyDTO.getPayType())){
            // 目前只支持余额支付
            throw new BizIllegalException("抱歉，目前只支持余额支付");
        }
        return payOrderService.applyPayOrder(applyDTO);
    }

    @Operation(summary = "尝试基于用户余额支付")
    @Parameter(description = "支付单id", name = "id")
    @PostMapping("{id}")
    public void tryPayOrderByBalance(@PathVariable("id") Long id, @Valid @RequestBody PayOrderFormDTO payOrderFormDTO){
        payOrderFormDTO.setId(id);
        payOrderService.tryPayOrderByBalance(payOrderFormDTO);
    }

    /**
     * 按业务订单号查询支付状态（返回三态结果，见 {@link PayOrderStatusDTO}）。
     *
     * 【注意】"查询成功但没有支付单"（found=false，determined=true）与"查询本身失败"
     * （Feign 降级为 determined=false）语义不重叠：调用方把"未知"当成"未支付"，
     * 就会把已付款的订单关掉并退回库存。
     */
    @Operation(summary = "根据业务订单号查询支付状态（三态：已支付/未支付/未知）")
    @GetMapping("/biz/{id}")
    public PayOrderStatusDTO queryPayOrderByBizOrderNo(@PathVariable("id") Long id){
        PayOrder payOrder = payOrderService.lambdaQuery().eq(PayOrder::getBizOrderNo, id).one();
        // 归属校验：前端必须查自己的单；llmall-trade-service 的延迟消息消费端无登录上下文
        // （UserContext 为 null）属内部调用，放行。订单号连号可猜，不校验归属即可遍历他人支付单。
        Long userId = UserContext.getUser();
        if (payOrder != null && userId != null && payOrder.getBizUserId() != null
                && !userId.equals(payOrder.getBizUserId())) {
            throw new ForbiddenException("无权查看他人支付单");
        }
        if (payOrder == null) {
            // 查询成功且确实没有支付单：这是确定的业务结论，与降级时的"未知"必须分开表达
            return PayOrderStatusDTO.notFound();
        }
        return PayOrderStatusDTO.of(payOrder.getStatus());
    }

    /**
     * 关闭支付单（交易订单退款成功后的账本回写）。
     *
     * 幂等：已关闭重复调用返回成功；不存在或无法关闭时抛错，交易域保留待办继续恢复。
     */
    @Operation(summary = "关闭支付单（已支付订单退款后回写支付账本）")
    @Parameter(name = "bizOrderNo", description = "交易订单号", in = ParameterIn.PATH, required = true)
    @PutMapping("/{bizOrderNo}/close")
    public boolean closePayOrder(@PathVariable("bizOrderNo") Long bizOrderNo,
            @org.springframework.web.bind.annotation.RequestHeader(value="x-service-token", required=false) String token) {
        if (!org.springframework.util.StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) {
            throw new ForbiddenException("内部接口，禁止外部调用");
        }
        return payOrderService.closePayOrder(bizOrderNo);
    }
}
