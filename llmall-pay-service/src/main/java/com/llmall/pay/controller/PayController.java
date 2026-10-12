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

    @Operation(summary = "查询支付单")
    @GetMapping
    public List<PayOrderVO> queryPayOrders(){
        // 只返回自己的支付单。原来直接 list() 是全表返回：支付单里带着金额、业务订单号、
        // 支付状态，任意一个登录用户都能看到"别人买了什么、付了多少钱"，
        // 是最容易被漏掉的越权读（接口本身没有任何参数，看起来"很安全"）。
        // 按创建时间倒序，与前端"最近支付记录在前"的展示顺序一致。
        Long userId = UserContext.getUser();
        if (userId == null) {
            // 不要把"未登录"当成"没有数据"返回空列表：那会让前端把鉴权问题显示成"暂无记录"，
            // 把真正的故障藏起来。
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
    // 少了 @Valid，PayApplyDTO 上的 @NotNull/@Min 就只是注释：amount 传 0 或者干脆不传都能进到业务里，
    // 只能靠下游兜（而下游兜不住时就是"0 元支付成功"）。入参边界必须在入口这一层生效。
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
     * 按业务订单号查询支付状态（返回**三态**结果，见 {@link PayOrderStatusDTO}）。
     *
     * <p>为什么不是"返回支付单，查不到就返回 null"：调用方是交易域的超时关单逻辑，
     * 它需要区分"确认没支付"与"查不到"。用 null 表达"没有"时，本接口一旦不可用，
     * 调用方就会把"未知"当成"未支付"把关单流程走完 —— 用户付了钱、订单被关、库存退回。
     * 所以这里把"查询成功但没有支付单"显式表达成 found=false（determined=true），
     * 而"查询本身失败"由 Feign 降级表达成 determined=false，两者语义不重叠。
     */
    @Operation(summary = "根据业务订单号查询支付状态（三态：已支付/未支付/未知）")
    @GetMapping("/biz/{id}")
    public PayOrderStatusDTO queryPayOrderByBizOrderNo(@PathVariable("id") Long id){
        PayOrder payOrder = payOrderService.lambdaQuery().eq(PayOrder::getBizOrderNo, id).one();
        // 归属校验。这个路径有两个调用方，必须区别对待：
        //  ① 前端：拿着自己的订单号查支付状态 —— 有登录态，必须是自己的单；
        //  ② llmall-trade-service 的延迟消息消费端（判断超时订单是否已支付）—— MQ 线程没有登录上下文，
        //     属于内部调用，此时 UserContext 为 null，放行。
        // 只按 bizOrderNo 查而不校验归属的话，订单号是连号可猜的，
        // 遍历一遍就能拿到"某笔订单付了多少钱、付没付"。
        Long userId = UserContext.getUser();
        if (payOrder != null && userId != null && payOrder.getBizUserId() != null
                && !userId.equals(payOrder.getBizUserId())) {
            throw new ForbiddenException("无权查看他人支付单");
        }
        if (payOrder == null) {
            // 查询成功 + 支付单确实不存在 —— 这是一个**确定的业务结论**（用户从没发起过支付），
            // 与降级时的"未知"必须分开表达
            return PayOrderStatusDTO.notFound();
        }
        return PayOrderStatusDTO.of(payOrder.getStatus());
    }

    /**
     * 关闭支付单（交易订单退款成功后的账本回写）。
     *
     * 幂等：重复调用只会有一次真正把状态改掉，其余返回"未发生变更"。
     * 交易域按返回值记日志，不因支付单"没什么可改的"而把退款判成失败。
     */
    @Operation(summary = "关闭支付单（已支付订单退款后回写支付账本）")
    @Parameter(name = "bizOrderNo", description = "交易订单号", in = ParameterIn.PATH, required = true)
    @PutMapping("/{bizOrderNo}/close")
    public boolean closePayOrder(@PathVariable("bizOrderNo") Long bizOrderNo) {
        return payOrderService.closePayOrder(bizOrderNo);
    }
}
