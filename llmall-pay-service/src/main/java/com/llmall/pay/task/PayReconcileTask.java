package com.llmall.pay.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmall.pay.domain.po.PayOrder;
import com.llmall.pay.enums.PayStatus;
import com.llmall.pay.service.IPayOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 支付日终对账：把实时扣款链路无法判定事实的支付单筛出来交给人工核对，避免呆账。
 *
 * 【核心职责】只读扫描，输出两类可疑支付单，并把支付单 id 打进日志作为人工介入的入口：
 * 1. 待对账标记：result_code=UNCERTAIN（扣款结果未知，实时链路无法确定时写入的留痕）；
 * 2. 长期挂起：状态仍是"待支付"但已超过支付超时时间。
 *
 * 【与实时兜底的分工】实时链路已经做了两层保护：
 * 1. 幂等键按订单维度确定（{@code PAY:<支付单id>:<金额>}）+ 下游余额流水唯一键
 *    {@code uk_biz(biz_type, biz_no)} —— 从构造上杜绝"同一订单被扣两次"；
 * 2. 三态责任模型（PENDING/DEDUCTED/UNKNOWN）+ 超时同键重试 ——
 *    把"响应丢失"这一类不确定态尽量收敛成确定态。
 * 但仍有一类情况实时链路必须主动放弃判断：连续重试后扣款结果依然不可知
 * （{@link com.llmall.pay.service.impl.PayOrderServiceImpl} 的 UNKNOWN 分支）。
 * 此时"退款"可能凭空加钱、"标记已支付"可能放行未付款订单，两者都不可接受，
 * 只能留痕并把判断交给本任务 —— 长期挂起的待支付单则是交易域关单链路失效的信号。
 *
 * 【为什么不自动修正】自动修正的前提是能向资金侧回答"这个幂等键到底扣没扣"，
 * 即下游具备"按业务号查余额流水"的查询能力，当前不具备；信息不足时的自动修正本身就是新的资损来源
 * （把已扣款的订单退款 = 凭空加钱）。因此本任务只报告、不修改。
 *
 * 【人工介入】触发条件是日志里出现"需人工介入"的扣款结果未知支付单；期望动作是按该单 result_msg 中的
 * 幂等键核对用户域余额流水 —— 确认已扣则退款或推进状态，确认未扣则直接放行重试。
 * 长期挂起的支付单只需排查交易域关单链路，不用改动支付单本身。
 * 下游补上该查询能力后，把 {@link #settle} 的报告分支换成"查询—判定—补偿"即可，框架无需重写。
 *
 * 【注意】本任务不自动退款、不自动改状态：它只在无法确定事实时给出可执行线索。
 *
 * 【注意】依赖启动类上的 {@code @EnableScheduling}：漏了注解不会有任何报错，只是对账永远不跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayReconcileTask {

    /**
     * 单轮每类可疑支付单最多取多少条：限制查询行数与告警输出规模。
     * 【注意】两类查询都按 createTime 升序取前 N 条，异常单超过该值时本轮只报最早的那一批，
     * 靠后的要等前面的滑出对账窗口后才会被报出 —— 异常量突增时不能只看"任务跑完了"。
     */
    private static final int BATCH_SIZE = 200;

    /**
     * 对账窗口（小时）：只看近期订单，更早的订单若仍有异常说明已被前面的窗口反复报过，继续扫只会淹没告警。
     * 【调小后果】异常单会在人工看到之前滑出窗口，直接漏报。
     * 【调大后果】同一批未处理的异常单每轮被重复报出，告警噪音放大。
     */
    private static final int RECONCILE_WINDOW_HOURS = 48;

    /** 支付中状态的卡死阈值（分钟）：一次余额支付在秒级完成，超过该值视为收尾钩子未执行 */
    private static final int STUCK_PAYING_MINUTES = 10;

    /** 防重入：定时触发与运维手动触发可能撞在一起（CAS 失败的那一轮直接跳过，不排队） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final IPayOrderService payOrderService;

    /**
     * 定时入口：cron 由 {@code llmall.pay.reconcile-cron} 覆盖，默认值落在凌晨低峰，与交易域对账错开时段、避免同抢 DB 连接。
     * 【关联配置】改 cron 时必须确认它与交易域对账任务不再重叠。
     * 【注意】调成高频不会提高对账的覆盖面：本任务只报告不修改，未处理的异常单每轮都会被重新报出。
     */
    @Scheduled(cron = "${llmall.pay.reconcile-cron:0 0 1 * * ?}")
    public void scheduledReconcile() {
        reconcile();
    }

    /**
     * 执行一轮对账；定时与运维手动入口共用。
     *
     * 【返回】本轮报出的可疑支付单数（待对账 + 长期挂起），日志里会分别给出数量与支付单 id。
     * 【注意】返回 0 不等于"账实一致"：重入被跳过、本轮执行异常同样返回 0，
     * 两种情况都会留下 warn/error 日志，判定必须结合日志，不能只看返回值。
     */
    public int reconcile() {
        if (!running.compareAndSet(false, true)) {
            log.warn("支付日终对账已在进行中，本次跳过");
            return 0;
        }
        try {
            return settle();
        } catch (Exception e) {
            // 异常不能让定时任务终止：Spring 会把异常记进日志并继续后续调度；
            // 但这里只能返回 0，在告警侧看起来与"账实一致"一样，所以异常必须留在日志里
            log.error("支付日终对账执行异常", e);
            return 0;
        } finally {
            running.set(false);
        }
    }

    private int settle() {
        LocalDateTime windowStart = LocalDateTime.now().minusHours(RECONCILE_WINDOW_HOURS);

        List<PayOrder> uncertain = listUncertain(windowStart);
        List<PayOrder> stuckPending = listStuckPending(windowStart);
        List<PayOrder> stuckPaying = listStuckPaying(windowStart);

        if (uncertain.isEmpty() && stuckPending.isEmpty() && stuckPaying.isEmpty()) {
            log.info("支付日终对账完成：窗口 {} 小时内无待对账/长期挂起订单", RECONCILE_WINDOW_HOURS);
            return 0;
        }

        // 扣款结果未知的单优先级最高：它们"可能已经扣了钱却没推进状态"，因此用 error 级别报警
        if (!uncertain.isEmpty()) {
            log.error("【需人工介入】发现 {} 笔扣款结果未知的支付单，请按 result_msg 中的幂等键核对余额流水"
                            + "（确认已扣则退款或推进状态，确认未扣则直接放行重试）: payOrderIds={}",
                    uncertain.size(), idsOf(uncertain));
        }
        if (!stuckPending.isEmpty()) {
            log.warn("发现 {} 笔超过支付超时时间仍为待支付的支付单，建议检查交易域关单链路是否正常: payOrderIds={}",
                    stuckPending.size(), idsOf(stuckPending));
        }
        // 支付中卡死说明进程在扣款过程中退出或收尾钩子未执行：钱可能已扣，必须人工核对
        if (!stuckPaying.isEmpty()) {
            log.error("【需人工介入】发现 {} 笔长时间停留在支付中的支付单，请核对余额流水后归位或推进: payOrderIds={}",
                    stuckPaying.size(), idsOf(stuckPaying));
        }

        log.warn("支付日终对账完成：待对账 {} 笔，长期挂起 {} 笔，支付中卡死 {} 笔",
                uncertain.size(), stuckPending.size(), stuckPaying.size());
        return uncertain.size() + stuckPending.size() + stuckPaying.size();
    }

    /** 扣款结果未知的支付单（实时链路写入的 UNCERTAIN 标记） */
    private List<PayOrder> listUncertain(LocalDateTime windowStart) {
        return payOrderService.list(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getResultCode, "UNCERTAIN")
                .ge(PayOrder::getCreateTime, windowStart)
                .orderByAsc(PayOrder::getCreateTime)
                .last("LIMIT " + BATCH_SIZE));
    }

    /** 已超过支付超时时间、状态仍为"待支付"的支付单 */
    private List<PayOrder> listStuckPending(LocalDateTime windowStart) {
        return payOrderService.list(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getStatus, PayStatus.WAIT_BUYER_PAY.getValue())
                .lt(PayOrder::getPayOverTime, LocalDateTime.now())
                .ge(PayOrder::getCreateTime, windowStart)
                .orderByAsc(PayOrder::getCreateTime)
                .last("LIMIT " + BATCH_SIZE));
    }

    /** 长时间停留在"支付中"（4）的支付单：正常一次支付在秒级完成，超过阈值即视为卡死 */
    private List<PayOrder> listStuckPaying(LocalDateTime windowStart) {
        return payOrderService.list(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getStatus, PayStatus.PAYING.getValue())
                .lt(PayOrder::getCreateTime, LocalDateTime.now().minusMinutes(STUCK_PAYING_MINUTES))
                .ge(PayOrder::getCreateTime, windowStart)
                .orderByAsc(PayOrder::getCreateTime)
                .last("LIMIT " + BATCH_SIZE));
    }

    private String idsOf(List<PayOrder> orders) {
        return orders.stream().map(o -> String.valueOf(o.getId())).collect(Collectors.joining(","));
    }
}
