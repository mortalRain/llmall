package com.llmall.shop.task;

import com.llmall.common.constant.MqConstants;
import com.llmall.shop.client.ConsumeStatusDTO;
import com.llmall.shop.client.SearchClient;
import com.llmall.shop.domain.po.MessageLog;
import com.llmall.shop.domain.vo.HotReconcileResultVO;
import com.llmall.shop.service.IMessageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 门店热度事件对账任务（审计 B4 的第三块：对账兜底）。
 *
 * <h3>解决什么</h3>
 * 本地消息表 + 重试任务只能保证"消息最终发进了 MQ"，**保证不了下游把它处理成功**
 * （消费者处理到一半宕机、ack 丢失、消息进了死信队列）。这一段的缺口原来完全没人管：
 * "评价存在、门店热度/搜索索引不变"可以永久存在，而且没有任何信号。
 *
 * <h3>思路：问下游"你到底处理了没有"</h3>
 * 搜索域在消费时会把 {@code (biz_type=SHOP_HOT_CHANGED, biz_id, consumer=llmall-search-service)}
 * 写进自己的 consume_record（与业务更新同事务）。本任务拿 message_log 里"已投递但还没被确认"
 * 的行去问搜索域 {@code GET /search/consume-status}：
 *  - 已处理 → 把消息置为 DONE（对账窗口不会无限重复检查同一批事件）；
 *  - 明确未处理 → 补偿重发（下游消费幂等，重发安全）；
 *  - 未知 → **不下结论**，既不算已处理也不补发，留到下一轮再查。
 *
 * <h3>为什么对账必须是三态（照抄 trade/OrderReconcileTask 的血泪口径）</h3>
 * "查询失败"与"查到了、结果是没有"在数据上长得很像，业务含义却相反：前者只能说明"我还不知道"，
 * 后者才是"确实没处理"。原实现（trade 那边）把查询失败当成"已处理"，
 * 于是下游没实例时全部检查失败、日志却打印"下游均已处理"——一致性缺口被伪装成正常。
 * 这里的规则：
 *  - 未知**不计入**"已处理"，下一轮再查（下游恢复后自然能查出结论）；
 *  - 三个计数分开打印，未知数 > 0 用 WARN，连续多轮未知升级为 ERROR 提醒人工介入。
 *
 * <h3>为什么要留"宽限期"</h3>
 * 消息刚投递出去就被对账，会读到"下游还没来得及消费"从而判成未处理 →
 * 每轮对账都重复补发一次，形成补偿风暴。所以只对账**投递超过 GRACE_SECONDS 秒**的消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopSearchReconcileTask {

    /** 只对账最近 24 小时的事件：更早的由人工/离线对账处理，避免任务无限膨胀 */
    private static final int RECONCILE_WINDOW_HOURS = 24;
    private static final int BATCH_SIZE = 100;

    /** 宽限期（秒）：投递后不足这么久的消息不参与对账，避免"刚发出去就问"引发补偿风暴 */
    private static final int GRACE_SECONDS = 60;

    /** 连续多少轮出现"状态未知"就升级为 ERROR（下游长时间不可用需要人看一眼，而不是一直 WARN） */
    private static final int UNKNOWN_ALERT_ROUNDS = 3;

    /** 下游处理状态：三态，未知与"未处理"必须严格区分 */
    private enum DownstreamState {
        /** 已处理 */
        CONSUMED,
        /** 明确未处理 → 需要补偿 */
        NOT_CONSUMED,
        /** 查询失败/降级 → 不知道，既不算已处理，也不触发补偿（下轮再看） */
        UNKNOWN
    }

    private final IMessageLogService messageLogService;
    private final SearchClient searchClient;

    /** 连续出现"未知"的轮数：用于把"下游一直不可用"从 WARN 升级成需要人工介入的 ERROR */
    private final AtomicInteger consecutiveUnknownRounds = new AtomicInteger(0);

    /**
     * 防重入：定时触发与运维接口手动触发可能撞在一起。
     * 对账本身是幂等的（补发也安全），但两个线程同时跑会有重复补发与重复日志，
     * 用一把进程内的开关挡住即可（多实例部署时各实例各自对账，同样不会错）。
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
     * 执行一轮对账。运维接口也可以直接调用（演示/排障时不必等 5 分钟）。
     *
     * @return 本轮三个口径的计数
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
                    // 只对"已投递但未确认"的行对账：
                    //  PENDING/FAILED 属于"还没发出去"，是 MessageRetryTask 的活；
                    //  DONE 是已经确认过的，不必再看。两者分工见 MessageRetryTask 的注释。
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
                        // 置为 DONE：下一轮不再检查这一条，对账窗口不会无限增长
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

    /**
     * 打印本轮结果。
     * 口径必须分开：把未知混进"均已处理"正是原缺陷的核心，
     * 所以文案里"已处理 A / 未处理 B / 状态未知 C"三个数字都要出现。
     */
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
     * <p>查询失败 = 未知，**不是**"已处理"也不是"未处理"：返回 UNKNOWN 让调用方保留待下轮确认。
     * 这里不再触发补偿，是因为补偿的前提是"确认下游没处理"，
     * 在不知道的情况下重发，既可能做无用功，也可能在下游已经过载时雪上加霜。
     *
     * <p>另外注意 {@link ConsumeStatusDTO#getConsumed()} 是包装类型：
     * 响应体里没有这个字段（对端版本不一致/被网关改写）时会拿到 null，同样按未知处理 ——
     * 若用基本类型 boolean，这种"响应不可信"会被静默当成"明确未处理"，直接引发补偿风暴。
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
