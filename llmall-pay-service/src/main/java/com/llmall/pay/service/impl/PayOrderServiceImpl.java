package com.llmall.pay.service.impl;
import com.llmall.common.constant.MqConstants;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.client.TradeClient;
import com.llmall.api.client.UserClient;
import com.llmall.api.dto.MoneyDeductDTO;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.UserContext;
import com.llmall.common.trace.TraceContext;
import com.llmall.pay.domain.dto.PayApplyDTO;
import com.llmall.pay.domain.dto.PayOrderFormDTO;
import com.llmall.pay.domain.po.PayOrder;
import com.llmall.pay.enums.PayStatus;
import com.llmall.pay.mapper.PayOrderMapper;
import com.llmall.pay.service.IPayOrderService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

/** 支付订单 服务实现类。@author 同城生活 @since 2023-05-16 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderServiceImpl extends ServiceImpl<PayOrderMapper, PayOrder> implements IPayOrderService {

    /** 支付锁的 key 前缀：锁的粒度是支付单，不是用户也不是全局 */
    private static final String PAY_LOCK_KEY_PREFIX = "pay:lock:";
    /** 锁的 TTL（秒）：兜底防持锁进程崩溃后死锁，取值要大于一次余额支付的正常耗时 */
    private static final long PAY_LOCK_TTL_SECONDS = 30L;

    /** 释放锁的 Lua：值一致才删，直接 DEL 会误删锁超时后别人刚拿到的那把 */
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final UserClient userClient;

    private final TradeClient tradeClient;

    private final RabbitTemplate rabbitTemplate;

    private final StringRedisTemplate redisTemplate;

    /**
     * 故障注入开关（默认关闭）：扣款成功后人为抛异常，用来验证"钱已扣 → 流程失败 → 自动退款补偿 + 支付单归位"
     * 这条链路。为什么需要它：这条路径的下游校验全部发生在扣款之前，正常操作几乎无法稳定复现，
     * 而它恰恰是最不能出错的一段（出错就是用户钱没了）。生产环境必须保持 false（默认值即 false）。
     * 用法（只在本机验证时）：{@code --llmall.pay.fault-injection.after-deduct=true}
     */
    @Value("${llmall.pay.fault-injection.after-deduct:false}")
    private boolean faultInjectAfterDeduct;

    @Override
    public String applyPayOrder(PayApplyDTO applyDTO) {
        // 1.先确认这笔交易订单允不允许付，再谈生成支付单：越早失败，需要人工对账的差错单越少
        checkOrderPayable(applyDTO);
        // 2.幂等性校验
        PayOrder payOrder = checkIdempotent(applyDTO);
        return payOrder.getId().toString();
    }

    /** 支付前的订单侧校验：订单存在、归属当前用户、仍待付款、金额一致（支付单字段都来自前端，不可信） */
    private void checkOrderPayable(PayApplyDTO applyDTO) {
        TradeOrderDTO tradeOrder;
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
        }
        if (tradeOrder == null || tradeOrder.getId() == null) {
            throw new BizIllegalException("交易订单不存在，无法生成支付单");
        }
        // 2.归属校验：UserContext 为 null 说明是服务间内部调用（没有登录态），跳过；有登录态时必须是自己的订单
        Long userId = UserContext.getUser();
        if (userId != null && tradeOrder.getUserId() != null && !userId.equals(tradeOrder.getUserId())) {
            throw new ForbiddenException("无权为他人订单支付");
        }
        // 3.只有待付款能生成支付单：已支付/已关闭还来申请说明前端状态早已过期，必须明确报错而不是默默建单，否则会重现订单已超时关闭、钱却付了的资损路径
        if (tradeOrder.getStatus() == null || tradeOrder.getStatus() != 1) {
            throw new BizIllegalException("订单已支付或已关闭，无法生成支付单");
        }
        // 4.金额单位为分，必须与交易订单的应付金额严格相等：amount 是前端传的，不比对就等于允许把 100 元改成 1 元
        if (tradeOrder.getTotalFee() == null || !tradeOrder.getTotalFee().equals(applyDTO.getAmount())) {
            throw new BizIllegalException("支付金额与订单金额不一致");
        }
    }

    /**
     * 基于余额支付。状态机：0 未提交 / 1 待支付 / 2 已关闭 / 3 支付成功。
     * 整段包在支付单维度的 Redis 锁里：双击与重试会让两个请求都通过待支付检查，从而重复扣款或互相覆盖状态，
     * 表里又没有可用的支付中中间态，所以后来的请求立刻失败而不排队等锁；扣款幂等由锁 + 状态机只允许 1→3 推进一次保证。
     * 扣款是跨库远程调用，本地事务回滚退不回钱，所以扣款成功后任何失败都必须显式退款补偿。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void tryPayOrderByBalance(PayOrderFormDTO payOrderFormDTO) {
        Long payOrderId = payOrderFormDTO.getId();
        // ---- 并发闸门：同一支付单同一时刻只允许一个支付流程 ----
        String lockKey = PAY_LOCK_KEY_PREFIX + payOrderId;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(PAY_LOCK_TTL_SECONDS));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BizIllegalException("支付处理中，请勿重复提交");
        }

        // ---- 本次支付尝试的幂等键 ----
        // 口径：PAY:<支付单id>:<本次请求的 traceId>
        //  · 为什么不能只用订单号：这次尝试失败会把钱退回去，用户随后**重新支付**是一次新的资金动作；
        //    沿用同一个业务号会被下游当成"已扣过款"而跳过扣款 → 用户不花钱就拿到订单（比重复扣款更严重）；
        //  · 为什么带 traceId 而不是随机数：Feign 内部重试、上游重试复用同一条请求的 traceId，
        //    于是"同一次尝试"天然幂等，"另一次尝试"天然是一笔新业务。
        String attemptId = TraceContext.current() == null
                ? UUID.randomUUID().toString().replace("-", "") : TraceContext.current();
        String deductBizNo = "PAY:" + payOrderId + ":" + attemptId;
        String refundBizNo = "PAYFAIL:" + deductBizNo;

        // 扣款的"资金责任"：在扣款之前就把事务收尾钩子注册好 —— 这样"扣款成功后任何失败"
        // （方法内异常回滚、以及**提交阶段**失败）都在退款补偿的保护范围内（见 registerTransactionCompletion）
        PaymentLiability liability = new PaymentLiability(payOrderId, refundBizNo);
        registerTransactionCompletion(lockKey, lockValue, liability);

        try {
            // 1.查询支付单
            PayOrder po = getById(payOrderFormDTO.getId());
            if (po == null) {
                throw new BizIllegalException("支付单不存在");
            }
            // 1.1 归属校验：支付单 id 可枚举，不校验的话任何人都能拿别人的支付单 id、用自己的余额替别人付掉；先校验身份，也不泄露订单状态
            Long currentUserId = UserContext.getUser();
            if (po.getBizUserId() != null && !po.getBizUserId().equals(currentUserId)) {
                throw new ForbiddenException("无权支付他人订单");
            }
            // 2.判断支付单状态
            if(!PayStatus.WAIT_BUYER_PAY.equalsValue(po.getStatus())){
                throw new BizIllegalException("交易已支付或关闭！");
            }
            // 3.扣款前必须回查交易订单并比对金额：订单侧有超时未支付自动关单，用户关单后才付就会变成订单已关闭、余额却扣了；金额不一致则等于允许改金额少付
            TradeOrderDTO tradeOrder = tradeClient.queryOrderById(po.getBizOrderNo());
            if (tradeOrder == null) {
                throw new BizIllegalException("交易订单不存在，无法支付");
            }
            if (tradeOrder.getStatus() == null || tradeOrder.getStatus() != 1) {
                throw new BizIllegalException(tradeOrder.getStatus() != null && tradeOrder.getStatus() == 5
                        ? "订单已超时关闭，无法支付" : "订单状态已变更（当前状态：" + tradeOrder.getStatus() + "），无法支付");
            }
            if (tradeOrder.getTotalFee() != null && !tradeOrder.getTotalFee().equals(po.getAmount())) {
                throw new BizIllegalException("支付金额与订单金额不一致，无法支付");
            }
            // 4.尝试扣减余额（这一步之后钱就已经离开用户账户，必须为它负责到底）
            Long payerId = po.getBizUserId() != null ? po.getBizUserId() : currentUserId;
            Integer deductedAmount = po.getAmount();
            liability.arm(payerId, deductedAmount);
            // 支付密码改走请求体，避免出现在请求行里被 Feign 日志与 access log 记录；
            // bizNo 是本次尝试的幂等键：下游按它落资金流水并去重，重复到达不会二次扣款
            userClient.deductMoney(new MoneyDeductDTO()
                    .setPw(payOrderFormDTO.getPw())
                    .setAmount(deductedAmount)
                    .setBizNo(deductBizNo));
            liability.markDeducted();
            // 故障注入（默认关闭）：扣款成功后人为失败，用来验证"钱已扣 → 失败 → 自动退款"这条链路。
            // 没有它这条路径在演示环境几乎无法稳定复现（下游校验都在扣款之前），而它恰恰是最不能出错的一段。
            if (faultInjectAfterDeduct) {
                throw new IllegalStateException("【故障注入】扣款后人为失败（llmall.pay.fault-injection.after-deduct=true），"
                        + "用于验证资金补偿与事务回滚后的归位");
            }
            // 5.修改支付单状态（条件更新 1→3；失败说明状态已被改走，钱必须退）
            boolean success = markPayOrderSuccess(payOrderFormDTO.getId(), LocalDateTime.now());
            if (!success) {
                throw new BizIllegalException("交易已支付或关闭！");
            }
            // 6.通知交易域改订单状态（支付成功的消息发送失败不影响支付本身，由对账兜底）
            try {
                rabbitTemplate.convertAndSend(MqConstants.PAY_EXCHANGE, MqConstants.PAY_SUCCESS_KEY, po.getBizOrderNo());
            } catch (Exception e) {
                log.error("支付成功的消息发送失败，支付单id：{}， 交易单id：{}", po.getId(), po.getBizOrderNo(), e);
            }
        } catch (RuntimeException e) {
            // 4.x 补偿：扣款之后失败必须把钱退回去，否则就是钱扣了、支付单还是待支付，用户会重复支付且账对不上；
            //     退款失败只记日志（需人工介入），但绝不能吞掉原始异常。
            //     这里立即退一次是"快路径"（用户马上看到钱回来了）；事务收尾钩子里还会兜一次（覆盖提交阶段失败），
            //     两次用的是同一个退款幂等键 refundBizNo，重复执行不会重复退钱。
            if (liability.isDeducted()) {
                compensateDeduct(liability.getPayOrderId(), liability.getPayerId(), liability.getAmount(),
                        liability.getRefundBizNo(), e);
                liability.markCompensated();
            } else {
                log.warn("支付失败且尚未扣款，无需退款补偿: payOrderId={}, 原因={}", payOrderId, e.getMessage());
            }
            // 5.x 状态归位与释放锁都交给事务收尾钩子（registerTransactionCompletion）：它们都必须在
            //     事务真正结束之后执行 —— 在方法体里做，写入会被随后的回滚一起带走、锁也会提前放开。
            throw e;
        }
        // 注意：这里**没有** finally 释放锁 —— 见 registerTransactionCompletion 的说明
    }

    /**
     * 事务收尾（提交或回滚之后执行）：状态归位 → 退款补偿 → 释放锁。
     *
     * <h3>为什么这三件事都必须在这里做，而不是在方法体的 finally / catch 里</h3>
     * <ol>
     *   <li><b>释放锁</b>：方法体的 finally 在 @Transactional 提交**之前**执行，锁一放开，
     *       另一个请求立刻拿到锁、读到还没提交的支付单（仍是"待支付"）→ 通过检查 → 重复扣款。
     *       锁必须活到事务真正结束。</li>
     *   <li><b>状态归位</b>：回滚会把事务内的写入一起带走，所以"把支付单从中间态改回待支付"
     *       只能在回滚之后做。</li>
     *   <li><b>退款补偿</b>：方法体内的 catch 只能覆盖"方法体抛异常"，覆盖不了
     *       <b>提交阶段失败</b>（此时方法已正常返回、catch 根本不会执行，而钱已经扣了）；
     *       而提交失败时 Spring 传进来的 status 是 {@code STATUS_UNKNOWN}，所以判据必须是
     *       "不等于 COMMITTED"，不能只判 ROLLED_BACK。</li>
     * </ol>
     * 退款按 refundBizNo 幂等，因此与快路径的补偿重复执行也只会退一次钱。
     */
    private void registerTransactionCompletion(String lockKey, String lockValue, PaymentLiability liability) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 理论上不会发生（本方法由 @Transactional 调用）；真发生了也必须放锁，否则要等 TTL
            log.warn("事务同步未激活，支付收尾钩子未注册（锁将在 TTL 后自动过期）: payOrderId={}", liability.getPayOrderId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        restoreWaitPayIfNotTerminal(liability.getPayOrderId());
                        if (liability.isDeducted() && !liability.isCompensated()) {
                            compensateDeduct(liability.getPayOrderId(), liability.getPayerId(), liability.getAmount(),
                                    liability.getRefundBizNo(),
                                    new IllegalStateException("支付事务未提交（status=" + status + "），已扣款项必须退回"));
                            liability.markCompensated();
                        }
                    }
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            }
        });
    }

    /** 事务未提交时把支付单归位为待支付：只收敛 0/1 这类非终态 —— 已是 3 或 2 的账本不能被改回来 */
    private void restoreWaitPayIfNotTerminal(Long payOrderId) {
        try {
            boolean reset = lambdaUpdate()
                    .eq(PayOrder::getId, payOrderId)
                    .in(PayOrder::getStatus, PayStatus.NOT_COMMIT.getValue(), PayStatus.WAIT_BUYER_PAY.getValue())
                    .set(PayOrder::getStatus, PayStatus.WAIT_BUYER_PAY.getValue())
                    .update();
            log.warn("支付流程未提交，支付单已归位为待支付: payOrderId={}, 是否发生变更={}", payOrderId, reset);
        } catch (Exception e) {
            log.error("支付单归位为待支付失败（状态仍为原值，可重试）: payOrderId={}", payOrderId, e);
        }
    }

    /**
     * 一次支付尝试里的"资金责任"：扣款成功后就一直挂着，直到事务提交（责任解除）或未提交（必须退款）。
     * 用可变对象而不是方法局部变量，是因为要跨 catch 块与事务收尾钩子共享状态。
     */
    private static final class PaymentLiability {
        private final Long payOrderId;
        private final String refundBizNo;
        private volatile Long payerId;
        private volatile Integer amount;
        private volatile boolean deducted;
        private volatile boolean compensated;

        private PaymentLiability(Long payOrderId, String refundBizNo) {
            this.payOrderId = payOrderId;
            this.refundBizNo = refundBizNo;
        }

        /** 记录付款人与金额（补偿时要用），在真正扣款之前调用 */
        private void arm(Long payerId, Integer amount) {
            this.payerId = payerId;
            this.amount = amount;
        }

        private void markDeducted() {
            this.deducted = true;
        }

        private void markCompensated() {
            this.compensated = true;
        }

        private boolean isDeducted() {
            return deducted;
        }

        private boolean isCompensated() {
            return compensated;
        }

        private Long getPayOrderId() {
            return payOrderId;
        }

        private String getRefundBizNo() {
            return refundBizNo;
        }

        private Long getPayerId() {
            return payerId;
        }

        private Integer getAmount() {
            return amount;
        }
    }

    /** 扣款补偿：把已经扣掉的余额退回付款人，并留下需要核对的痕迹（退款按 refundBizNo 幂等，重复补偿只退一次） */
    private void compensateDeduct(Long payOrderId, Long payerId, Integer amount, String refundBizNo, Throwable cause) {
        if (payerId == null || amount == null) {
            log.error("支付失败且扣款信息不完整，无法自动退款，需人工核对: payOrderId={}", payOrderId, cause);
            return;
        }
        try {
            // 复用用户域已有的退款接口：它与订单退款入账是同一条资金路径，不新造只有支付域会走的加钱通道，避免对账口径分叉。
            // bizNo 用 "PAYFAIL:<扣款业务号>"：与扣款一一对应，快路径与事务收尾各补偿一次也只退一次钱
            userClient.refundMoney(payerId, amount, refundBizNo);
            log.error("支付流程失败且已扣款，已自动退款补偿: payOrderId={}, payerId={}, amount={}, bizNo={}，原因={}",
                    payOrderId, payerId, amount, refundBizNo, cause == null ? "未知" : cause.getMessage(), cause);
        } catch (Exception refundError) {
            log.error("【需人工介入】支付流程失败且自动退款补偿也失败，用户余额可能已被扣走: payOrderId={}, payerId={}, amount={}, bizNo={}",
                    payOrderId, payerId, amount, refundBizNo, refundError);
        }
    }

    /** 释放锁：必须"先比对值再删"，否则会把锁过期后别人拿到的那把锁误删 */
    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            // 释放失败不是业务错误：锁有 TTL 会自己过期，记日志即可，不能因此让支付请求失败
            log.error("释放支付锁失败（锁将在 TTL 后自动过期）: lockKey={}", lockKey, e);
        }
    }

    public boolean markPayOrderSuccess(Long id, LocalDateTime successTime) {
        return lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_SUCCESS.getValue())
                .set(PayOrder::getPaySuccessTime, successTime)
                .eq(PayOrder::getId, id)
                // 支付状态的乐观锁判断
                .in(PayOrder::getStatus, PayStatus.NOT_COMMIT.getValue(), PayStatus.WAIT_BUYER_PAY.getValue())
                .update();
    }


    private PayOrder checkIdempotent(PayApplyDTO applyDTO) {
        // 1.查询支付单；2.不存在说明是第一次，写入新支付单并返回
        PayOrder oldOrder = queryByBizOrderNo(applyDTO.getBizOrderNo());
        if (oldOrder == null) {
            PayOrder payOrder = buildPayOrder(applyDTO);
            payOrder.setPayOrderNo(IdWorker.getId());
            save(payOrder);
            return payOrder;
        }
        // 3.已支付成功、4.已关闭：都是终态，不能再发起支付
        if (PayStatus.TRADE_SUCCESS.equalsValue(oldOrder.getStatus())) {
            throw new BizIllegalException("订单已经支付！");
        }
        if (PayStatus.TRADE_CLOSED.equalsValue(oldOrder.getStatus())) {
            throw new BizIllegalException("订单已关闭");
        }
        // 5.支付渠道不一致：重置旧单数据后重新申请支付单；6.渠道一致且未支付/未提交：直接复用旧单
        if (!StringUtils.equals(oldOrder.getPayChannelCode(), applyDTO.getPayChannelCode())) {
            PayOrder payOrder = buildPayOrder(applyDTO);
            payOrder.setId(oldOrder.getId());
            payOrder.setQrCodeUrl("");
            updateById(payOrder);
            payOrder.setPayOrderNo(oldOrder.getPayOrderNo());
            return payOrder;
        }
        return oldOrder;
    }

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

    /**
     * 关闭支付单：条件更新 0/1/3 → 2（已关闭）。必须条件更新而不是查到就 set：退款与扣款会并发，无条件覆盖会把刚写入的支付成功抹成已关闭，
     * 结果是钱扣了、账本却是关闭的；用 status in (...) 限定源状态后谁先成功谁作数，重复调用天然幂等。
     * 允许 3 → 2 不是抹掉支付记录：退款场景下这笔钱确实一度支付成功过，所以这条路径只能由交易域在已退款确认之后调用；
     * 否则对账时支付单是支付成功、交易单是已退款关闭，两者矛盾，分不清用户付了钱还是钱已经退回去了。
     * 交易订单号始终是两侧的关联键，故这里把支付单收敛到终态 2，由交易侧订单状态 + close_time 表达是退款关闭的——
     * 支付域回答这笔钱还挂在支付流程里吗，交易域回答为什么关闭；没有支付单或状态已变都返回 false 而不抛异常：两者都是正常的收敛结果，此时退款已把钱还给用户，不该因支付账本无可改而整体失败。
     */
    @Override
    public boolean closePayOrder(Long bizOrderNo) {
        if (bizOrderNo == null) {
            throw new BadRequestException("业务订单号不能为空");
        }
        PayOrder payOrder = queryByBizOrderNo(bizOrderNo);
        if (payOrder == null) {
            log.info("退款回写支付单：该交易订单没有支付单，无需关闭，bizOrderNo={}", bizOrderNo);
            return false;
        }
        // 归属校验：这个接口也会被前台调用（网关注入 user-info），不能让别人关掉你的支付单
        Long userId = UserContext.getUser();
        if (userId != null && payOrder.getBizUserId() != null && !userId.equals(payOrder.getBizUserId())) {
            throw new ForbiddenException("无权关闭他人支付单");
        }
        boolean closed = lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_CLOSED.getValue())
                .eq(PayOrder::getId, payOrder.getId())
                .in(PayOrder::getStatus, PayStatus.NOT_COMMIT.getValue(),
                        PayStatus.WAIT_BUYER_PAY.getValue(), PayStatus.TRADE_SUCCESS.getValue())
                .update();
        if (closed) {
            log.info("退款回写支付单：支付单已关闭（原状态 {}），payOrderId={}, bizOrderNo={}",
                    payOrder.getStatus(), payOrder.getId(), bizOrderNo);
        } else {
            log.warn("退款回写支付单：支付单当前状态为 {}，未做变更（可能已关闭），payOrderId={}, bizOrderNo={}",
                    payOrder.getStatus(), payOrder.getId(), bizOrderNo);
        }
        return closed;
    }
}
