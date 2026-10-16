package com.llmall.pay.service.impl;
import com.llmall.common.constant.IdempotentKeys;
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
import com.llmall.common.trace.BizTrace;
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

/**
 * 支付单的申请、余额支付与关闭：把本地支付账本与跨库余额扣减这两件不能放进同一个事务的事对齐。
 *
 * 【核心职责】
 * 1. applyPayOrder：校验交易订单可付（归属、状态、金额）后，按交易订单号复用或新建支付单
 * 2. tryPayOrderByBalance：支付单维度加锁 → 订单维度条件更新闸门 → 跨服务扣款 → 条件更新为支付成功
 * 3. closePayOrder：交易域退款确认后，把支付账本条件更新为已关闭
 *
 * 【资金口径】金额单位一律为分。扣款幂等键 = PAY:<支付单id>:<金额>，退款幂等键 = PAYFAIL:<扣款键>，
 * 两者统一由 IdempotentKeys 构造，本类不手拼。每笔扣款/退款在用户域都对应一条余额流水，
 * 由唯一键 uk_biz(biz_type, biz_no) 去重 —— 这是"重试、快路径与事务收尾重复补偿都只会真正动一次钱"的最终依据。
 *
 * 【注意】余额扣减是跨服务远程调用，本地事务回滚退不回钱：扣款成功后任何失败都必须显式退款补偿，
 * 且只对"确定已扣"的状态退款（见 PaymentLiability）。
 *
 * @author 同城生活
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderServiceImpl extends ServiceImpl<PayOrderMapper, PayOrder> implements IPayOrderService {

    /** 支付锁 key 前缀，锁的粒度是支付单，不是用户也不是全局 */
    private static final String PAY_LOCK_KEY_PREFIX = "pay:lock:";
    /**
     * 支付锁 TTL（秒）：兜底防止持锁进程崩溃后死锁。
     * 【取值依据】一次余额支付 = 最多 2 次同键扣款尝试 + 一到两次本地条件更新，正常耗时远小于该值。
     * 【调小后果】一次支付还没结束锁就自动过期，并发请求即可拿到锁并通过待支付检查，同一支付单出现多个支付尝试。
     * 【调大后果】持锁进程崩溃后，该支付单在 TTL 到期前无法再被支付（释放锁只发生在事务收尾之后）。
     */
    private static final long PAY_LOCK_TTL_SECONDS = 30L;

    /** 释放锁的 Lua：值一致才删，直接 DEL 会误删锁超时后别人刚拿到的那把（脚本参数见 releaseLock） */
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 扣款结果不确定的标记值，写入 {@code pay_order.result_code}。
     *
     * 【设计意图】余额支付不经过第三方渠道，本列在余额支付链路上本来没有第三方业务码要写；
     * 用它承载"待对账"标记，可以在不新增表、不改对外字段的前提下让日终对账精确筛出这批支付单
     * （判据见 PayReconcileTask：result_code=UNCERTAIN 且在建单窗口内）。
     * 【注意】result_msg 是给人工看的：里面必须留幂等键（PAY:<支付单id>:<金额>），否则对不出这笔钱扣没扣。
     */
    static final String RESULT_CODE_UNCERTAIN = "UNCERTAIN";

    /** 四段式埋点的业务类型标识（见 llmall-common 的 BizTrace） */
    private static final String BIZ_TYPE_PAY = "PAY";

    private final UserClient userClient;

    private final TradeClient tradeClient;

    private final RabbitTemplate rabbitTemplate;

    private final StringRedisTemplate redisTemplate;

    /**
     * 故障注入开关（默认关闭）：扣款成功后人为抛异常，用来走一遍"已扣款 → 流程失败 → 退款补偿 + 支付单归位"链路；
     * 该路径的下游校验全部发生在扣款之前，正常操作无法稳定复现。
     *
     * 【风险】它就留在支付主链路的方法体里，唯一保护是默认值 false（没有 profile 守卫、没有环境判断）：
     * 一旦被启动参数或配置中心置为 true，每一次余额支付都会在扣款成功后抛异常，全部进入"扣款 → 退款"路径 ——
     * 用户余额被扣又退回、支付单归位为待支付，资金链路持续抖动，且日志会把它记成支付失败而不是配置错误。
     * 【严禁】生产环境必须保持 false：不得写进任何共享/环境配置，只能在验证环境用启动参数临时打开
     * （{@code --llmall.pay.fault-injection.after-deduct=true}），验证结束后立即重启去掉该参数。
     */
    @Value("${llmall.pay.fault-injection.after-deduct:false}")
    private boolean faultInjectAfterDeduct;

    @Override
    public String applyPayOrder(PayApplyDTO applyDTO) {
        // 先校验交易订单是否可付再生成支付单：越早失败，需要人工对账的差错单越少
        checkOrderPayable(applyDTO);
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
    }

    /**
     * 基于余额支付：状态机 0 未提交 / 1 待支付 / 2 已关闭 / 3 支付成功，本路径只允许 1 → 3 推进一次。
     *
     * 【入参约束】id 由控制器用路径变量补入，不能为 null；pw 是明文支付密码，只随扣款请求透传给用户域校验，不落库、不进日志。
     * 【异常】BizIllegalException：支付单不存在 / 状态已变更 / 金额不一致 / 未获得支付锁；ForbiddenException：支付单不属于当前登录用户。
     *
     * 【幂等与并发裁决点】整段包在支付单维度的 Redis 锁里，且不排队等锁 —— 表里没有可用的"支付中"中间态，
     * 两个并发请求都会通过待支付检查，所以后来的请求必须立即失败而不是排队等待。拿到锁之后还有第二道裁决：
     * claimDeductAttempt 以订单维度的条件更新（要求 status 仍为 1）决定谁能继续往下走；
     * 扣款金额的最终去重落在下游余额流水唯一键上（见 deductMoneyIdempotently）。
     *
     * 【失效条件】锁只对"这一次方法调用"有效：锁 TTL 内没能跑完（例如下游扣款连续超时），
     * 并发请求即可拿锁并通过状态检查；条件更新的判据只有 status 列，任何人放宽该条件（例如允许 2/3 再次进入扣款）
     * 都会让重复推进无人阻挡。三道防线（锁 / 状态条件更新 / 下游唯一键）缺一不可。
     *
     * 【风险】误改的后果：去掉锁或改成排队等锁后，同一支付单会同时存在多个支付尝试，各自推进状态、各自承担退款责任；
     * 一种交错是后到的请求在自己的条件更新失败后按"确定已扣"发起退款，于是钱退回去了、支付单却已是支付成功。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void tryPayOrderByBalance(PayOrderFormDTO payOrderFormDTO) {
        Long payOrderId = payOrderFormDTO.getId();
        // 四段式埋点第 1 段【入口】：后续 IDEMPOTENT/RESULT/COST 都带同一 bizId，可直接 grep 串联
        long traceStart = BizTrace.enter(BIZ_TYPE_PAY, payOrderId);
        String lockKey = PAY_LOCK_KEY_PREFIX + payOrderId;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(PAY_LOCK_TTL_SECONDS));
        if (!Boolean.TRUE.equals(locked)) {
            // 入口已埋点：这里补上耗时与结果，避免留下"只有 ENTER 没有后续"的半截链路
            BizTrace.result(BIZ_TYPE_PAY, payOrderId, false, "未获得支付锁（并发重复提交）");
            BizTrace.cost(BIZ_TYPE_PAY, payOrderId, traceStart);
            throw new BizIllegalException("支付处理中，请勿重复提交");
        }

        // 扣款前先把事务收尾钩子注册好，使"扣款成功后任何失败"（方法内异常回滚、提交阶段失败）
        // 都落在退款补偿的保护范围内，见 registerTransactionCompletion
        // 【注意】责任对象在拿到支付单金额/付款人之后才 arm（含幂等键），此处先建空壳
        PaymentLiability liability = new PaymentLiability(payOrderId);
        registerTransactionCompletion(lockKey, lockValue, liability);

        try {
            PayOrder po = getById(payOrderFormDTO.getId());
            if (po == null) {
                throw new BizIllegalException("支付单不存在");
            }
            // 归属校验：支付单 id 可枚举，不校验的话任何人都能拿别人的支付单 id、用自己的余额替别人付掉；先校验身份，也不泄露订单状态
            Long currentUserId = UserContext.getUser();
            if (po.getBizUserId() != null && !po.getBizUserId().equals(currentUserId)) {
                throw new ForbiddenException("无权支付他人订单");
            }
            // 【订单维度幂等闸门】只允许 status=1（待支付）进入扣款流程：
            // 并发/重放请求在这里被条件更新挡住，不会各自发起一次跨服务扣款
            if (!claimDeductAttempt(payOrderFormDTO.getId())) {
                throw new BizIllegalException("交易已支付或关闭！");
            }
            // 扣款前必须回查交易订单并比对金额：订单侧有超时未支付自动关单，用户关单后才付就会变成订单已关闭、余额却扣了；金额不一致则等于允许改金额少付
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
            // 扣减余额：这一步之后钱就已经离开用户账户，必须为它负责到底
            Long payerId = po.getBizUserId() != null ? po.getBizUserId() : currentUserId;
            Integer deductedAmount = po.getAmount();
            // 资金幂等键统一由 IdempotentKeys 构造（键形态只有一个定义点，见该类注释）：
            // 扣款键 = PAY:<支付单id>:<金额>（订单维度确定）；退款键 = PAYFAIL:<扣款键>（与扣款键不同值）
            String deductBizNo = IdempotentKeys.payDeduct(payOrderId, deductedAmount);
            liability.arm(payerId, deductedAmount, deductBizNo, IdempotentKeys.payFailRefund(deductBizNo));
            BizTrace.idempotent(BIZ_TYPE_PAY, payOrderId, deductBizNo, "扣款前幂等登记，来源=服务端生成");
            // 支付密码改走请求体，避免出现在请求行里被 Feign 日志与 access log 记录
            deductMoneyIdempotently(liability, payOrderFormDTO.getPw());
            if (faultInjectAfterDeduct) {
                throw new IllegalStateException("【故障注入】扣款后人为失败（llmall.pay.fault-injection.after-deduct=true），"
                        + "用于验证资金补偿与事务回滚后的归位");
            }
            // 条件更新 1→3；失败说明状态已被改走，钱必须退
            boolean success = markPayOrderSuccess(payOrderFormDTO.getId(), LocalDateTime.now());
            if (!success) {
                throw new BizIllegalException("交易已支付或关闭！");
            }
            // 通知交易域改订单状态：支付成功的消息发送失败不影响支付本身，由对账兜底
            try {
                rabbitTemplate.convertAndSend(MqConstants.PAY_EXCHANGE, MqConstants.PAY_SUCCESS_KEY, po.getBizOrderNo());
            } catch (Exception e) {
                log.error("支付成功的消息发送失败，支付单id：{}， 交易单id：{}", po.getId(), po.getBizOrderNo(), e);
            }
        } catch (RuntimeException e) {
            // 兜底：走到这里说明"本次尝试没能把支付推进成功"。只有 DeductionState.DEDUCTED（确定已扣）才退款，
            // 绝不能对 UNKNOWN 退款 —— 那会在一笔已成功扣款的支付上凭空加钱。
            // 未确定（UNKNOWN）的款项不在这里猜：由事务收尾钩子归位为待支付，账实不符由对账兜底。
            if (liability.getState() == DeductionState.DEDUCTED) {
                compensateDeduct(liability.getPayOrderId(), liability.getPayerId(), liability.getAmount(),
                        liability.getRefundBizNo(), e);
                liability.markCompensated();
            } else {
                log.warn("支付失败且扣款状态未确认为已扣（state={}），本路径不触发退款以免凭空加钱: payOrderId={}, 原因={}",
                        liability.getState(), payOrderId, e.getMessage());
            }
            // 埋点第 3/4 段：失败路径同样要留结果与耗时 ——
            // "慢失败"（例如下游超时）恰恰是最需要耗时数据的场景，只在成功路径埋点会丢掉它
            BizTrace.result(BIZ_TYPE_PAY, payOrderId, false, "支付未完成: " + e.getMessage());
            BizTrace.cost(BIZ_TYPE_PAY, payOrderId, traceStart);
            throw e;
        }
        // 埋点第 3/4 段【执行结果 / 耗时】：成功路径
        BizTrace.result(BIZ_TYPE_PAY, payOrderId, true, "余额支付完成");
        BizTrace.cost(BIZ_TYPE_PAY, payOrderId, traceStart);
        // 刻意不在此处 finally 释放锁，也不在此处做状态归位：二者都必须在事务真正结束后执行，
        // 见 registerTransactionCompletion
    }

    /**
     * 订单维度的扣款幂等闸门：仅当支付单仍是"待支付"时才允许本次尝试继续。
     *
     * 【设计意图】用条件更新而不是先查后判：先查后判在并发下两个请求都会通过，各自发起一次跨服务扣款。
     * 这里不改变支付单的可见状态（仍写 1，保持对外语义不变），只让"谁能继续往下走"由数据库裁决一次。
     * 【失效条件】判据只有 status=1 这一个条件：where 一旦被放宽（例如允许已关闭/已成功的支付单再次进入扣款），
     * 本闸门即失效；它也不覆盖"锁已过期但上一个事务尚未结束"的窗口。
     * 【返回】true 表示本次拿到闸门，false 表示状态已被并发请求改走，调用方必须立即失败并且不能扣款。
     */
    private boolean claimDeductAttempt(Long payOrderId) {
        boolean claimed = lambdaUpdate()
                .eq(PayOrder::getId, payOrderId)
                .eq(PayOrder::getStatus, PayStatus.WAIT_BUYER_PAY.getValue())
                .set(PayOrder::getStatus, PayStatus.WAIT_BUYER_PAY.getValue())
                .update();
        if (!claimed) {
            // 可能已被并发请求推进为 3 或关闭为 2：错误提示与支付单申请侧的失败语义保持一致
            log.warn("扣款尝试未获得闸门（支付单状态已变更）: payOrderId={}", payOrderId);
        }
        return claimed;
    }

    /**
     * 带幂等确认的扣款：先用同一幂等键重试，再决定钱到底扣没扣。
     *
     * 【背景】扣款是跨服务调用，"超时"有两种截然不同的含义：
     *   ① 请求没发出去 —— 钱没扣；
     *   ② 请求发出去了、下游也扣了，只是响应没回来 —— 钱已经扣了。
     * 两者在调用方看来完全一样，只按"正常返回"判定已扣的话，②会被当成"没扣款、无需补偿"，用户重试即再扣一次。
     *
     * 【做法】扣款幂等键是订单维度确定的（同一支付单同一金额恒定，见 IdempotentKeys），重试同一个键是安全的：
     *   - 若①：重试会真正扣款；
     *   - 若②：重试被下游的余额流水唯一键跳过，下游按"这笔已扣"正常返回。
     * 因此"不确定"时用同键重试一次即可把状态收敛为确定；仍无法确定则标记 UNKNOWN，
     * 由外层归位为待支付、留给对账处理，绝不猜测后退款或猜成功后标记支付（见 PaymentLiability）。
     *
     * 【入参约束】pw 为明文支付密码，随扣款请求透传给用户域校验，本类不落库、不记日志。
     * 【异常】重试后仍不确定时抛出下游原始异常；非 RuntimeException 包装成 BizIllegalException 抛出。
     * 【注意】误改的后果：尝试次数调成 1，"响应丢失"这一类不确定态无法收敛，UNKNOWN 与待对账量上升；
     * 调大则延长持锁时间，一旦超过 PAY_LOCK_TTL_SECONDS，锁会在支付结束前过期。
     */
    private void deductMoneyIdempotently(PaymentLiability liability, String pw) {
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                userClient.deductMoney(new MoneyDeductDTO()
                        .setPw(pw)
                        .setAmount(liability.getAmount())
                        .setBizNo(liability.getDeductBizNo()));
                // 正常返回 = 下游确认"这笔幂等键的扣款已生效"（无论是本次扣的，还是上次已扣被跳过的）
                liability.markDeducted();
                return;
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    // 用同一幂等键重试以消除"响应丢失"的不确定性；下游按唯一键去重，不会二次扣款
                    log.warn("扣款调用未确认（第 {}/{} 次），使用同一幂等键重试以确认扣款状态: payOrderId={}, bizNo={}",
                            attempt, maxAttempts, liability.getPayOrderId(), liability.getDeductBizNo(), e);
                    continue;
                }
                // 重试后仍不确定：标记 UNKNOWN（不是"未扣"），交由外层归位与对账，绝不在这里猜
                liability.markUnknown();
                markDeductUncertain(liability.getPayOrderId(), liability.getDeductBizNo(), e);
                log.error("【需对账】扣款调用连续 {} 次未取得确定结果，扣款状态未知，"
                                + "支付单将归位为待支付并交由日终对账处理（不自动退款，避免凭空加钱）: payOrderId={}, bizNo={}",
                        maxAttempts, liability.getPayOrderId(), liability.getDeductBizNo(), e);
                throw e instanceof RuntimeException ? (RuntimeException) e : new BizIllegalException("扣款失败", e);
            }
        }
    }

    /**
     * 把"扣款结果不确定"这一状态落到支付单上，作为日终对账的输入。
     *
     * 【设计意图：为什么必须落库留痕】跨服务扣款结果未知时，内存里的状态随方法返回就消失了：
     * 支付单会被归位成"待支付"，与"从未发起过支付"完全无法区分，
     * 于是这笔"可能已经扣了钱"的订单永远不会被任何人复查 —— 这正是呆账的来源。
     * 这里把线索写进既有列（不新增表、不改对外字段）：
     *   - result_code = UNCERTAIN：机器可筛选的标记，日终对账按它取待对账单；
     *   - result_msg   = 幂等键 + 原因：人工核对余额流水时所需的全部线索。
     *
     * 【注意】写库失败只记日志，不影响主流程：对账是兜底手段，不能因为它失败而让支付报错；
     * 代价是这笔单退化成"看起来从未发起过支付"，只能靠这条 error 日志人工接手。
     */
    private void markDeductUncertain(Long payOrderId, String deductBizNo, Exception cause) {
        try {
            lambdaUpdate()
                    .eq(PayOrder::getId, payOrderId)
                    .set(PayOrder::getResultCode, RESULT_CODE_UNCERTAIN)
                    .set(PayOrder::getResultMsg, "扣款结果未知，待对账: bizNo=" + deductBizNo
                            + ", cause=" + (cause == null ? "未知" : cause.getClass().getSimpleName()))
                    .update();
        } catch (Exception e) {
            log.error("【需对账】扣款状态未知标记写入失败，请以日志为准人工核对: payOrderId={}, bizNo={}",
                    payOrderId, deductBizNo, e);
        }
    }

    /**
     * 事务收尾（提交或回滚之后执行）：状态归位 → 退款补偿 → 释放锁，由 Spring 的事务同步机制在事务真正结束后触发。
     *
     * 【为什么必须挂在这里】释放锁与状态归位都不能放进方法体的 finally / catch：finally 在 @Transactional 提交之前执行，
     * 锁一放开，另一个请求立刻拿到锁、读到还没提交的支付单（仍是待支付）→ 通过检查 → 同一支付单出现多个支付尝试；
     * 状态归位的写入则会被回滚一起带走。补偿也必须挂在这里，才能覆盖方法体 catch 覆盖不到的提交阶段失败，
     * 此时 Spring 传进来的 status 是 {@code STATUS_UNKNOWN}，判据必须是"不等于 COMMITTED"而不能只判 ROLLED_BACK。
     *
     * 【幂等与重试】三步都不重试，重复执行都安全：归位只收敛 0/1 两个非终态，退款按 refundBizNo 去重，
     * 锁按值比对释放（锁已过期则什么也不删）。失败一律只留日志：归位失败打 error（状态仍是原值，可人工重放），
     * 退款失败打【需人工介入】。
     */
    private void registerTransactionCompletion(String lockKey, String lockValue, PaymentLiability liability) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 理论上不会发生（本方法由 @Transactional 调用）；真发生则锁只能等 TTL 自动过期
            log.warn("事务同步未激活，支付收尾钩子未注册（锁将在 TTL 后自动过期）: payOrderId={}", liability.getPayOrderId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        restoreWaitPayIfNotTerminal(liability.getPayOrderId());
                        // 只有"确定已扣"才退款：UNKNOWN 表示跨服务调用结果不可知，
                        // 对它退款会在"其实已扣成功"的情况下凭空给用户加钱
                        if (liability.getState() == DeductionState.DEDUCTED && !liability.isCompensated()) {
                            compensateDeduct(liability.getPayOrderId(), liability.getPayerId(), liability.getAmount(),
                                    liability.getRefundBizNo(),
                                    new IllegalStateException("支付事务未提交（status=" + status + "），已扣款项必须退回"));
                            liability.markCompensated();
                        } else if (liability.getState() == DeductionState.UNKNOWN) {
                            log.error("【需对账】支付事务未提交且扣款状态未知，请人工核对余额流水: payOrderId={}, bizNo={}",
                                    liability.getPayOrderId(), liability.getDeductBizNo());
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
     * 一次支付尝试里的"资金责任"：跨服务扣款一旦发起就一直挂着，直到事务提交（责任解除）
     * 或未提交（确定已扣则退款）。
     *
     * 【三态取值与处理方式】调用方（tryPayOrderByBalance 的 catch 与事务收尾钩子）必须按状态分支处理：
     *   - PENDING  ：尚未发起扣款，或发起了但结果未确认 —— 禁止退款：这笔钱可能其实已经扣了；
     *   - DEDUCTED ：下游确认过这笔幂等键的扣款已生效 —— 事务未提交时必须退款，按 refundBizNo 幂等；
     *   - UNKNOWN  ：用同一幂等键重试后仍拿不到确定结果 —— 禁止退款，只留痕（result_code=UNCERTAIN）交日终对账。
     *
     * 【为什么不能用一个 boolean】boolean 只有"扣了/没扣"两种取值，装不下 UNKNOWN：
     * 把"查不到结果"归到"没扣"，会让一笔其实已扣款的支付被当成未扣而放行重试（重复扣款/漏单）；
     * 归到"已扣"，又会对没扣成功的支付发起退款（凭空加钱）。唯一安全的做法是把"结果未知"单独表达出来，
     * 并对它不做任何资金动作 —— 未知不等于未支付。
     *
     * 【为什么是可变对象】状态要跨 catch 块与事务收尾钩子共享，方法局部变量传不出去。
     */
    private static final class PaymentLiability {
        private final Long payOrderId;
        private volatile Long payerId;
        private volatile Integer amount;
        /** 扣款幂等键（订单维度确定）：重试用同一个键，保证不会二次扣款 */
        private volatile String deductBizNo;
        /** 退款幂等键：与扣款键一一对应但不同值，避免"退款抵消另一笔扣款" */
        private volatile String refundBizNo;
        private volatile DeductionState state = DeductionState.PENDING;
        private volatile boolean compensated;

        private PaymentLiability(Long payOrderId) {
            this.payOrderId = payOrderId;
        }

        /** 登记付款人、金额与两个幂等键；必须在真正扣款之前调用 */
        private void arm(Long payerId, Integer amount, String deductBizNo, String refundBizNo) {
            this.payerId = payerId;
            this.amount = amount;
            this.deductBizNo = deductBizNo;
            this.refundBizNo = refundBizNo;
        }

        private void markDeducted() {
            this.state = DeductionState.DEDUCTED;
        }

        private void markUnknown() {
            this.state = DeductionState.UNKNOWN;
        }

        private void markCompensated() {
            this.compensated = true;
        }

        private DeductionState getState() {
            return state;
        }

        private boolean isCompensated() {
            return compensated;
        }

        private Long getPayOrderId() {
            return payOrderId;
        }

        private String getDeductBizNo() {
            return deductBizNo;
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

    /** 跨服务扣款的责任状态：确定已扣才允许退款，未知一律不猜（见 PaymentLiability） */
    private enum DeductionState {
        PENDING, DEDUCTED, UNKNOWN
    }

    /**
     * 扣款补偿：把已扣掉的余额退回付款人，并留下核对痕迹。
     *
     * 【触发点】两处，都只在状态为 DEDUCTED 时调用：tryPayOrderByBalance 的 catch（方法内异常）与
     * 事务收尾钩子（提交阶段失败），后者覆盖前者覆盖不到的情况。
     * 【幂等】退款走用户域既有的退款入账接口，按 refundBizNo（PAYFAIL:<扣款键>）去重，
     * 因此快路径与事务收尾各补偿一次也只退一次钱 —— 复用同一条资金路径，不新造只有支付域会走的加钱通道。
     * 【边界约束】本方法不重试：付款人或金额缺失时一律不发起退款（宁可不退也不能退错人/退错金额），
     * 退款调用失败只打【需人工介入】日志并返回，用户余额可能已被扣走。
     * 【人工介入】失败日志里带着 payerId、amount、bizNo，人工补退时复用同一个 bizNo 即可，重复入账会被下游唯一键挡住。
     */
    private void compensateDeduct(Long payOrderId, Long payerId, Integer amount, String refundBizNo, Throwable cause) {
        if (payerId == null || amount == null) {
            log.error("支付失败且扣款信息不完整，无法自动退款，需人工核对: payOrderId={}", payOrderId, cause);
            return;
        }
        try {
            // 复用用户域已有的退款接口：它与订单退款入账是同一条资金路径，不新造只有支付域会走的加钱通道，避免对账口径分叉
            // bizNo 用 "PAYFAIL:<扣款业务号>"：与扣款一一对应，快路径与事务收尾各补偿一次也只退一次钱
            userClient.refundMoney(payerId, amount, refundBizNo);
            log.error("支付流程失败且已扣款，已自动退款补偿: payOrderId={}, payerId={}, amount={}, bizNo={}，原因={}",
                    payOrderId, payerId, amount, refundBizNo, cause == null ? "未知" : cause.getMessage(), cause);
        } catch (Exception refundError) {
            log.error("【需人工介入】支付流程失败且自动退款补偿也失败，用户余额可能已被扣走: payOrderId={}, payerId={}, amount={}, bizNo={}",
                    payOrderId, payerId, amount, refundBizNo, refundError);
        }
    }

    /**
     * 释放锁：必须先比对值再删，否则会误删锁过期后别人刚拿到的那把。
     * 【入参约束】lockKey 为加锁时的 key，lockValue 为本次持锁写入的随机值（Lua 里即 KEYS[1] 与 ARGV[1]）。
     */
    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            // 释放失败不是业务错误：锁有 TTL 会自行过期，记日志即可，不能因此让支付请求失败
            log.error("释放支付锁失败（锁将在 TTL 后自动过期）: lockKey={}", lockKey, e);
        }
    }

    /**
     * 把支付单条件更新为支付成功（0/1 → 3），由余额支付链路在扣款确认后调用。
     *
     * 【入参约束】id 为支付单 id；successTime 为支付成功时间，由调用方取当前时间，不用数据库时间。
     * 【返回】true 表示本次确实把状态推进为 3；false 表示状态已被并发请求改走（例如已关闭为 2）。
     * 【注意】误改的后果：false 时钱已经扣了，必须走退款补偿而不是重试本更新；
     * where 条件若被放宽到任意状态，会把已关闭（2）的支付单改回支付成功，账本与交易域的退款结果相反。
     */
    public boolean markPayOrderSuccess(Long id, LocalDateTime successTime) {
        return lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_SUCCESS.getValue())
                .set(PayOrder::getPaySuccessTime, successTime)
                .eq(PayOrder::getId, id)
                // 乐观锁：仅限 0/1 这两个源状态推进为支付成功
                .in(PayOrder::getStatus, PayStatus.NOT_COMMIT.getValue(), PayStatus.WAIT_BUYER_PAY.getValue())
                .update();
    }


    /**
     * 按交易订单号复用或新建支付单，是"生成支付单"这一步的幂等入口。
     *
     * 【为什么按订单维度而不是按请求维度】申请支付单可以被页面刷新、超时重试反复触发，
     * 按请求建键（traceId、时间戳、随机数）会让每次触发都落一条新的可支付账本，同一笔交易出现多条支付单，
     * 扣款侧的"一单只扣一次"再无从判定。交易订单号是付款依据本身：它相同才是同一笔支付，
     * 因此以它为维度，重复申请一律收敛到同一条支付单。
     *
     * 【裁决点】查到旧单即复用，渠道不同则原地重置二维码等信息；因此真正的唯一性由
     * pay_order.biz_order_no 上的唯一键裁决 —— 本方法的"先查后插"在并发申请之间没有任何互斥。
     * 【失效条件】该唯一键被删除或降级为普通索引后，同一交易订单会被并发插入两条支付单。
     * 【返回】可继续支付的支付单（新建的、复用的、或重置过的），调用方只需取它的 id。
     * 【异常】BizIllegalException：已支付成功或已关闭等终态不能再发起支付。
     */
    private PayOrder checkIdempotent(PayApplyDTO applyDTO) {
        PayOrder oldOrder = queryByBizOrderNo(applyDTO.getBizOrderNo());
        if (oldOrder == null) {
            PayOrder payOrder = buildPayOrder(applyDTO);
            payOrder.setPayOrderNo(IdWorker.getId());
            save(payOrder);
            return payOrder;
        }
        // 已支付成功、已关闭都是终态，不能再发起支付
        if (PayStatus.TRADE_SUCCESS.equalsValue(oldOrder.getStatus())) {
            throw new BizIllegalException("订单已经支付！");
        }
        if (PayStatus.TRADE_CLOSED.equalsValue(oldOrder.getStatus())) {
            throw new BizIllegalException("订单已关闭");
        }
        // 支付渠道不一致：重置旧单数据后重新申请支付单；渠道一致且未支付/未提交：直接复用旧单
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

    /**
     * 关闭支付单：条件更新 0/1/3 → 2（已关闭）。
     * 【注意】必须条件更新而不是查到就 set：退款与扣款会并发，无条件覆盖会把刚写入的支付成功抹成已关闭，结果是钱扣了、账本却是关闭的。
     * 允许 3 → 2 不是抹掉支付记录：退款场景这笔钱确实一度支付成功过，故该路径只能由交易域在退款确认后调用，
     * 否则对账时支付单支付成功、交易单已退款关闭，两者矛盾；关闭原因由交易侧订单状态 + close_time 表达。
     * 没有支付单或状态已变都返回 false 而不抛异常：两者都是正常的收敛结果，此时退款已把钱还给用户，不该因支付账本无可改而整体失败。
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
        // 归属校验：该接口也会被前台调用（网关注入 user-info），不能让别人关掉你的支付单
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
