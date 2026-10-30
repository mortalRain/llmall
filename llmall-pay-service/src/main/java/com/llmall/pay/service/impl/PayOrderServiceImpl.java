package com.llmall.pay.service.impl;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.client.TradeClient;
import com.llmall.api.client.VoucherClient;
import com.llmall.pay.support.CouponPaymentEligibility;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.UserContext;
import com.llmall.pay.domain.dto.PayApplyDTO;
import com.llmall.pay.domain.dto.PayOrderFormDTO;
import com.llmall.pay.domain.po.PayOrder;
import com.llmall.pay.enums.PayStatus;
import com.llmall.pay.mapper.PayOrderMapper;
import com.llmall.pay.service.IPayOrderService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 支付单申请与查询；余额支付由持久化尝试协调器处理，远程调用不占用本地事务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderServiceImpl extends ServiceImpl<PayOrderMapper, PayOrder> implements IPayOrderService {

    private final TradeClient tradeClient;
    private final VoucherClient vouchers;
    private final BalancePaymentCoordinator coordinator;

    @Override
    public String applyPayOrder(PayApplyDTO applyDTO) {
        SegmentTimer timer = new SegmentTimer();
        try {
            // 【阶段一：远程校验，不持有任何数据库连接】
            // 先校验交易订单是否可付再生成支付单：越早失败，需要人工对账的差错单越少。
            // 这一步只发一次 Feign、只读 ThreadLocal，不取连接、不开事务 —— 顺序不能反：
            // 本方法刻意不加 @Transactional，任何把 Feign 包进事务的改动都会让一次 4~6 ms 的远程调用
            // 整段占住一条连接（50 并发下每条连接的平均排队时间远大于这个数，占住即放大排队）。
            checkOrderPayable(applyDTO, timer);
            // 【阶段二：单次连接完成"查重 + 建单"】见 checkIdempotent；成功路径只取一次连接。
            PayOrder payOrder = checkIdempotent(applyDTO, timer);
            return payOrder.getId().toString();
        } finally {
            // 分段耗时必须在 finally 里落：慢失败（下游超时、Feign 重连）恰恰是最需要分段数据的场景，
            // 只在成功路径打印会把最该看的那批样本丢掉
            timer.log("applyPayOrder", applyDTO.getBizOrderNo());
        }
    }

    /**
     * 申请支付单链路的分段计时器：只读、只记日志，不参与任何业务判定。
     *
     * 【为什么需要它】整条链路只有一个总耗时（1156 ms 量级）时，无法区分"慢在同步 Feign 查询"、
     * "慢在支付单落库"还是"慢在单号生成"，而这三者的处置方式完全不同（改调用方式 / 加索引 / 换生成策略）。
     * 分段之后按段名聚合 Avg/P99，才能指认瓶颈段。
     *
     * 【口径】每段用 {@link #begin()} 与 {@link #record} 成对包裹，只覆盖被测调用本身；
     * 段与段可以嵌套（外层段包含内层段），因此各段之和可能大于总耗时，判断时看段自身的绝对值。
     *
     * 【为什么一条日志打完所有段】一次请求只写一行，日志 IO 对被测链路的影响最小；
     * 若每段各写一行，压测时日志本身就会变成瓶颈，测出来的数字失真。
     *
     * 【为什么是 DEBUG 级】分段计时是定位工具，不是生产语义日志：常开 INFO 会让每个支付请求多出一行，
     * 既是日志量负担，也会让压测时的日志 IO 反过来影响被测链路。需要定位时按包名临时开级即可，不必改代码：
     * {@code -Dlogging.level.com.llmall.pay.service.impl.PayOrderServiceImpl=DEBUG}。
     * 关闭时连字符串都不拼（先判 {@code isDebugEnabled()}），对主链路的开销只有几次 nanoTime。
     *
     * 【边界约束】本类不写库、不发消息、不抛异常、不改变返回值；异常路径同样只在 finally 里记一行。
     */
    private static final class SegmentTimer {

        private final long startNanos = System.nanoTime();
        private final Map<String, Long> segments = new LinkedHashMap<>();

        /** 标记一段开始，返回值必须原样交给 {@link #record} */
        private long begin() {
            return System.nanoTime();
        }

        /** 结束一段并累加耗时（同名段会被累加，例如一次请求里的两次条件更新） */
        private void record(String name, long beginNanos) {
            long costMs = (System.nanoTime() - beginNanos) / 1_000_000L;
            segments.merge(name, costMs, Long::sum);
        }

        private long totalMs() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }

        /** 输出一行：phase 区分链路，seg 里是 `段名:毫秒` 的空格分隔列表 */
        private void log(String phase, Object bizKey) {
            if (!PayOrderServiceImpl.log.isDebugEnabled()) {
                return;
            }
            StringBuilder line = new StringBuilder();
            segments.forEach((name, costMs) -> line.append(' ').append(name).append(':').append(costMs));
            PayOrderServiceImpl.log.debug("[BIZ] stage=PROFILE phase={} bizKey={} totalMs={} seg={}",
                    phase, bizKey, totalMs(), line.length() == 0 ? "（无）" : line.substring(1));
        }
    }

    /** 支付前的订单侧校验：订单存在、归属当前用户、仍待付款、金额一致（支付单字段都来自前端，不可信） */
    private void checkOrderPayable(PayApplyDTO applyDTO, SegmentTimer timer) {
        TradeOrderDTO tradeOrder;
        long feignBegin = timer.begin();
        try {
            tradeOrder = tradeClient.queryOrderById(applyDTO.getBizOrderNo());
        } catch (FeignException e) {
            // llmall-trade-service 查不到订单时用业务错误体（非 2xx）回话，Feign 因此抛异常而不是给出 null；403 是越权，其余是订单不存在
            log.warn("查询交易订单被拒绝，bizOrderNo：{}，status：{}", applyDTO.getBizOrderNo(), e.status());
            if (e.status() == 403) {
                throw new ForbiddenException("无权为他人订单支付");
            }
            throw new BizIllegalException("交易订单不存在，无法生成支付单");
        } catch (Exception e) {
            log.error("查询交易订单失败，bizOrderNo：{}", applyDTO.getBizOrderNo(), e);
            throw new BizIllegalException("交易订单不存在，无法生成支付单");
        } finally {
            timer.record("trade.queryOrderById#1", feignBegin);
        }
        if (tradeOrder == null || tradeOrder.getId() == null
                || !tradeOrder.getId().equals(applyDTO.getBizOrderNo())) {
            throw new BizIllegalException("交易订单不存在，无法生成支付单");
        }
        // 归属校验：UserContext 为 null 说明是服务间内部调用（无登录态），跳过；有登录态时必须是自己的订单
        Long userId = UserContext.getUser();
        if (userId != null && tradeOrder.getUserId() != null && !userId.equals(tradeOrder.getUserId())) {
            throw new ForbiddenException("无权为他人订单支付");
        }
        // 只有待付款能生成支付单；已支付/已关闭必须明确报错而非默默建单，否则会重现"订单已超时关闭、钱却付了"的资损路径
        if (tradeOrder.getStatus() == null || tradeOrder.getStatus() != 1) {
            throw new BizIllegalException("订单已支付或已关闭，无法生成支付单");
        }
        // 金额单位为分，必须与交易订单的应付金额严格相等：amount 来自前端，不比对等于允许把 100 元改成 1 元
        if (tradeOrder.getTotalFee() == null || !tradeOrder.getTotalFee().equals(applyDTO.getAmount())) {
            throw new BizIllegalException("支付金额与订单金额不一致");
        }
        CouponPaymentEligibility.requireBound(tradeOrder, vouchers);
    }

    /** Remote calls run outside local transactions; every attempt is durable. */
    @Override
    public void tryPayOrderByBalance(PayOrderFormDTO form) {
        coordinator.pay(form.getId(), form.getPw());
    }

    private PayOrder checkIdempotent(PayApplyDTO applyDTO, SegmentTimer timer) {
        PayOrder candidate = buildPayOrder(applyDTO);
        // 预生成主键：既是 INSERT 的值，也是"这行是否由本次请求插入"的判据。
        // 新插入时驱动回填的就是它；命中唯一键时 LAST_INSERT_ID(id) 回填的是已有行 id，两者不同。
        long newId = IdWorker.getId();
        candidate.setId(newId);
        long idBegin = timer.begin();
        candidate.setPayOrderNo(IdWorker.getId());
        timer.record("pay.idWorker.getId", idBegin);
        long saveBegin = timer.begin();
        try {
            baseMapper.saveOrGetExisting(candidate);
        } finally {
            // 失败路径（唯一键/非空约束）同样要留一段：报错时也得能看出耗时花在 DB 上
            timer.record("pay.upsert", saveBegin);
        }
        Long effectiveId = candidate.getId();
        if (effectiveId != null && effectiveId.longValue() == newId) {
            // 新建：candidate 上就是要落库的那份数据，不必再查一次
            return candidate;
        }
        // 走到这里 = 唯一键命中，candidate.id 已被改写成已存在行的 id。
        // 回查仍按 biz_order_no（与旧实现同一判据），而不是直接采信 candidate.id：
        // 若 upsert 命中的是 pay_order_no 上另一行（单号碰撞），直接采信会把别人的支付单返回给本次请求。
        long queryBegin = timer.begin();
        PayOrder oldOrder;
        try {
            oldOrder = queryByBizOrderNo(applyDTO.getBizOrderNo());
        } finally {
            timer.record("pay.queryByBizOrderNo", queryBegin);
        }
        if (oldOrder == null) {
            // 理论上不可达：唯一键已裁决存在，回查却查不到（例如刚被物理删除）。
            // 此时绝不能返回 candidate.id —— 它可能指向一条与本次交易订单无关的记录。
            throw new BizIllegalException("支付单状态异常，请稍后重试");
        }
        // 已支付成功、已关闭都是终态，不能再发起支付
        if (PayStatus.TRADE_SUCCESS.equalsValue(oldOrder.getStatus())) {
            throw new BizIllegalException("订单已经支付！");
        }
        if (PayStatus.TRADE_CLOSED.equalsValue(oldOrder.getStatus())) {
            throw new BizIllegalException("订单已关闭");
        }
        if (!Integer.valueOf(1).equals(oldOrder.getStatus())) {
            throw new BizIllegalException("支付处理中，请稍后查看结果");
        }
        return oldOrder;
    }

    /**
     * 建单：状态直接落"待支付"，并按固定时长推算支付超时时间。
     * 【注意】支付单不会因超时自动关闭：过了超时时间它仍是待支付，由交易域关单链路收敛，
     * 日终对账正是按"已过 payOverTime 仍为待支付"识别长期挂起。
     */
    private PayOrder buildPayOrder(PayApplyDTO payApplyDTO) {
        PayOrder payOrder = BeanUtils.toBean(payApplyDTO, PayOrder.class);
        payOrder.setPayOverTime(LocalDateTime.now().plusMinutes(120L));
        payOrder.setStatus(PayStatus.WAIT_BUYER_PAY.getValue());
        payOrder.setBizUserId(UserContext.getUser());
        return payOrder;
    }
    public PayOrder queryByBizOrderNo(Long bizOrderNo) {
        return lambdaQuery()
                .eq(PayOrder::getBizOrderNo, bizOrderNo)
                .one();
    }

    /** 退款已到账后关闭已支付账本；未确认成功必须抛错，让交易域持久化任务重试。 */
    @Override
    public boolean closePayOrder(Long bizOrderNo) {
        if (bizOrderNo == null) {
            throw new BadRequestException("业务订单号不能为空");
        }
        PayOrder payOrder = queryByBizOrderNo(bizOrderNo);
        if (payOrder == null) {
            throw new BizIllegalException("退款支付账本不存在，等待核对");
        }
        // 归属校验：该接口也会被前台调用（网关注入 user-info），不能让别人关掉你的支付单
        Long userId = UserContext.getUser();
        if (userId != null && payOrder.getBizUserId() != null && !userId.equals(payOrder.getBizUserId())) {
            throw new ForbiddenException("无权关闭他人支付单");
        }
        boolean closed = lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_CLOSED.getValue())
                .eq(PayOrder::getId, payOrder.getId())
                .eq(PayOrder::getStatus, PayStatus.TRADE_SUCCESS.getValue())
                .update();
        if (closed) {
            log.info("退款回写支付单：支付单已关闭（原状态 {}），payOrderId={}, bizOrderNo={}",
                    payOrder.getStatus(), payOrder.getId(), bizOrderNo);
        } else {
            log.warn("退款回写支付单：支付单当前状态为 {}，未做变更（可能已关闭），payOrderId={}, bizOrderNo={}",
                    payOrder.getStatus(), payOrder.getId(), bizOrderNo);
        }
        if (closed) return true;
        PayOrder current = queryByBizOrderNo(bizOrderNo);
        if (current != null && Integer.valueOf(2).equals(current.getStatus())) return true;
        throw new BizIllegalException("支付账本尚未关闭，等待重试");
    }
}
