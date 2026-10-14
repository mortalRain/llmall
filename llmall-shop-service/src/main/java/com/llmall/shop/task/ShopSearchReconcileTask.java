package com.llmall.shop.task;

import com.llmall.common.constant.MqConstants;
import com.llmall.shop.client.ConsumeStatusDTO;
import com.llmall.shop.client.SearchClient;
import com.llmall.shop.domain.po.MessageLog;
import com.llmall.shop.domain.vo.HotReconcileResultVO;
import com.llmall.common.mq.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 门店热度事件对账：把 message_log 里"已投递但未确认"的门店热度事件拿去问搜索域的处理状态，
 * 已处理置 DONE，明确未处理则补偿重发，兜住"消息发出去了、下游却没处理成功"的缺口。
 *
 * 【注意】下游状态必须按三态判定：查询失败与"查到了、结果是没有"业务含义相反，
 * 未知既不计入已处理也不补发，否则一致性缺口会被伪装成正常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopSearchReconcileTask {

    /** 对账窗口（小时）：更早的事件不查，避免任务无限膨胀 */
    private static final int RECONCILE_WINDOW_HOURS = 24;
    private static final int BATCH_SIZE = 100;

    /** 宽限期（秒）：投递不足此时长的消息不参与对账，否则会把"下游尚未消费"误判为未处理，形成补偿风暴 */
    private static final int GRACE_SECONDS = 60;

    /** 连续出现"未知"达到该轮数就把日志级别升为 ERROR（下游长时间不可用需人工介入） */
    private static final int UNKNOWN_ALERT_ROUNDS = 3;

    /** 下游处理状态：三态，"未知"与"未处理"必须严格区分 */
    private enum DownstreamState {
        CONSUMED,
        /** 明确未处理 → 需要补偿重发 */
        NOT_CONSUMED,
        /** 查询失败/降级 → 不知道，既不算已处理也不触发补偿（下轮再查） */
        UNKNOWN
    }

    private final IMessageLogService<MessageLog> messageLogService;
    private final SearchClient searchClient;

    private final AtomicInteger consecutiveUnknownRounds = new AtomicInteger(0);

    /**
     * 防重入：定时触发与运维接口手动触发可能撞在一起，两个线程同时跑会重复补发、重复日志。
     * 【注意】这只是进程内开关，多实例部署时各实例各自对账（补发幂等，不会出错）。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            initialDelayString = "${llmall.shop.reconcile.initial-delay-ms:60000}",
            fixedDelayString = "${llmall.shop.reconcile.interval-ms:300000}")
    public void scheduledReconcile() {
        try {
            reconcile();
        } catch (Exception e) {
            // 定时任务绝不能因为单次异常而终止
            log.error("门店热度事件对账任务执行异常", e);
        }
    }

    /**
     * 执行一轮对账，运维接口也可直接调用（演示/排障时不必等定时触发）。
     *
     * @return 本轮"已处理 / 未处理 / 未知"三个口径的计数
     */
    public HotReconcileResultVO reconcile() {
        HotReconcileResultVO result = new HotReconcileResultVO();
        if (!running.compareAndSet(false, true)) {
            log.info("上一轮对账尚未结束，本轮跳过");
            return result;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<MessageLog> logs = messageLogService.lambdaQuery()
                    .eq(MessageLog::getBizType, MqConstants.BIZ_SHOP_HOT_CHANGED)
                    // 只对"已投递未确认"的行对账：PENDING/FAILED 归 MessageRetryTask，DONE 已确认不必再看
                    .eq(MessageLog::getStatus, MessageLog.STATUS_SENT)
                    .ge(MessageLog::getCreateTime, now.minusHours(RECONCILE_WINDOW_HOURS))
                    .le(MessageLog::getCreateTime, now.minusSeconds(GRACE_SECONDS))
                    .orderByAsc(MessageLog::getId)
                    .last("LIMIT " + BATCH_SIZE)
                    .list();
            if (logs.isEmpty()) {
                return result;
            }

            int consumed = 0;
            int missing = 0;
            int unknown = 0;
            for (MessageLog messageLog : logs) {
                DownstreamState state = safeConsumed(messageLog.getBizId());
                switch (state) {
                    case CONSUMED:
                        consumed++;
                        // 置 DONE 后下一轮不再检查这一条，对账窗口不会无限增长
                        messageLogService.markDone(messageLog.getId());
                        break;
                    case NOT_CONSUMED:
                        missing++;
                        log.warn("对账发现搜索域未处理该事件: bizId={}", messageLog.getBizId());
                        messageLogService.compensate(messageLog.getBizType(), messageLog.getBizId());
                        break;
                    default:
                        // 未知：不补发（避免补偿风暴）、也不算已处理（避免掩盖缺口）
                        unknown++;
                        break;
                }
            }
            result.setChecked(logs.size()).setConsumed(consumed).setMissing(missing).setUnknown(unknown);
            report(result);
            return result;
        } finally {
            running.set(false);
        }
    }

    /** 打印本轮结果：三个口径必须分开，"未知"不能混进"已处理" */
    private void report(HotReconcileResultVO r) {
        if (r.getUnknown() == 0) {
            consecutiveUnknownRounds.set(0);
            if (r.getMissing() > 0) {
                log.warn("门店热度对账完成：检查 {} 条，已处理 {}，未处理 {}（已补发），状态未知 0",
                        r.getChecked(), r.getConsumed(), r.getMissing());
            } else {
                log.info("门店热度对账完成：检查 {} 条，已处理 {}，未处理 0，状态未知 0",
                        r.getChecked(), r.getConsumed());
            }
            return;
        }
        int rounds = consecutiveUnknownRounds.incrementAndGet();
        if (rounds >= UNKNOWN_ALERT_ROUNDS) {
            log.error("【需人工介入】门店热度对账连续 {} 轮存在状态未知的事件：llmall-search-service 可能长时间不可用，"
                            + "一致性问题正在积累。检查 {} 条，已处理 {}，未处理 {}（已补发），状态未知 {}（未计入已处理，等待下轮重试）",
                    rounds, r.getChecked(), r.getConsumed(), r.getMissing(), r.getUnknown());
        } else {
            log.warn("门店热度对账完成：检查 {} 条，已处理 {}，未处理 {}（已补发），状态未知 {}（下游不可用，未计入已处理，等待下轮重试）",
                    r.getChecked(), r.getConsumed(), r.getMissing(), r.getUnknown());
        }
    }

    /**
     * 查询下游处理状态。
     *
     * 【注意】查询失败或响应不可信都返回 UNKNOWN 且不触发补偿：补偿的前提是"确认下游没处理"，
     * 不知道时重发既可能做无用功，也可能压垮已经过载的下游。
     * {@link ConsumeStatusDTO#getConsumed()} 必须是包装类型，否则响应缺字段会被静默当成"明确未处理"。
     */
    private DownstreamState safeConsumed(String bizId) {
        try {
            ConsumeStatusDTO status = searchClient.isConsumed(MqConstants.BIZ_SHOP_HOT_CHANGED, bizId);
            if (status == null || status.getConsumed() == null) {
                log.warn("查询搜索域消费状态返回不可信结果，本轮按'未知'处理: bizId={}, resp={}", bizId, status);
                return DownstreamState.UNKNOWN;
            }
            return status.getConsumed() ? DownstreamState.CONSUMED : DownstreamState.NOT_CONSUMED;
        } catch (Exception e) {
            log.warn("查询搜索域消费状态失败，本轮按'未知'处理（不计入已处理）: bizId={}, cause={}", bizId, e.getMessage());
            log.debug("查询搜索域消费状态失败详情", e);
            return DownstreamState.UNKNOWN;
        }
    }
}
