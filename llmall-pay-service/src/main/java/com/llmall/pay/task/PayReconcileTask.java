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
 * 历史支付与超时账本的只读告警。新支付尝试由 BalancePaymentCoordinator 自动恢复；
 * 本任务不修改资金。窗口外历史异常须使用升级检查或人工全量对账，不能以本任务返回0代表账实一致。
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
