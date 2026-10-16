package com.llmall.common.trace;

import lombok.extern.slf4j.Slf4j;

/**
 * 核心链路四段式埋点：入口 → 幂等校验 → 执行结果 → 耗时。
 *
 * 【为什么需要统一的埋点格式】本项目最贵的一类缺陷是静默失效 —— 不报错，只是行为与预期不一致
 * （退款抵消扣款、库存被夹到 0、缓存永不回填、对账永远差一笔）。
 * 这类问题靠"接口返回 200"完全测不出来，只能靠日志里能不能看出每一步的真实结果。
 * 四段式把一次业务动作拆成可检索的四个节点，排查从"通读代码猜哪里出问题"
 * 变成"按 bizId 检索，看它停在了哪一段"。
 *
 * 【字段口径】四段的字段名固定，检索与聚合都依赖它们：
 * stage：段名，取值 ENTER / IDEMPOTENT / RESULT / COST；
 * bizType：事件类型，业务动作的短常量名（PAY / SECKILL / VOUCHER / LOGIN / CART 等）；
 * bizId：业务号，支付单 id、券 token、订单号、userId 等，是串联四段的唯一依据；
 * bizNo：幂等键本身，仅 IDEMPOTENT 段有，用于核对键的形态是否符合预期；
 * costMs：耗时毫秒，仅 COST 段有，等于 COST 段的时间减去 ENTER 段返回的时间戳。
 *
 * 【调用顺序】enter → idempotent → result → cost，四段必须传同一组 bizType/bizId，否则串不起来。
 *
 * 【为什么不用 AOP 切面自动埋点】切面只能拿到方法签名与方法级耗时，拿不到业务语义：
 * 它不知道哪一步是幂等判定、判定结果是命中还是新执行、这次扣款是不是被补偿退回了，
 * 而这几个信息恰恰是资金链路排查的关键，因此采用显式埋点而不是切面。
 *
 * 【降级行为】本类只写日志：不写库、不发消息、不抛异常、不参与业务判定。
 * 因此日志系统不可用或某一段被漏埋时业务结果不受影响，代价只是排查时少一条线索。
 *
 * 【边界约束】正因为埋点不参与业务，异常必须在调用方的 catch 里处理并打 ERROR，
 * 禁止为了"让埋点跑完"而把异常包进 try/catch 吞掉 —— 那会把真实故障变成静默失效，
 * 而静默失效正是四段式要解决的那类缺陷。
 *
 * 【检索约定】所有日志统一带前缀 [BIZ]，后接固定的 stage= / bizType= / bizId= 键值对，
 * 因此可直接按 bizId 检索单个业务动作的完整四段，或按 stage=RESULT success=false 检索全部失败动作。
 *
 * @author 同城生活
 * @since 2026-10
 */
@Slf4j
public final class BizTrace {

    /** 统一前缀：便于日志采集按关键字过滤，也便于与框架日志区分 */
    private static final String TAG = "[BIZ]";

    private BizTrace() {
    }

    /**
     * 第一段【入口】：记录业务动作开始，返回起始时间戳供 {@link #cost} 使用。
     *
     * 【注意】入参里不要放口令、令牌、完整手机号等敏感信息：
     * 日志会被采集与长期留存，写进去就无法撤回，脱敏责任在调用方。
     *
     * @param bizType 业务类型，建议用短常量：PAY / SECKILL / VOUCHER / LOGIN / CART
     * @param bizId   业务标识（支付单 id、券 token、订单号、userId 等），用于串联四段
     * @param detail  关键入参摘要（可为 null）
     * @return 起始时间戳（毫秒），必须原样传给 {@link #cost}
     */
    public static long enter(String bizType, Object bizId, String detail) {
        long start = System.currentTimeMillis();
        log.info("{} stage=ENTER bizType={} bizId={} detail={}", TAG, bizType, bizId, detail);
        return start;
    }

    /**
     * 第二段【幂等校验】：记录幂等键与判定结果。
     *
     * 【为什么单独成段】资金缺陷的根因大多在幂等上（键选错、键共用、键可被调用方控制）。
     * 把"键是什么 + 判定结果"独立打一行，出问题时能立刻确认键的形态是否符合预期，
     * 而不必去翻代码或猜。
     *
     * @param judgement 判定结果的文字描述，例如"新执行"/"幂等命中跳过"/"键冲突"
     */
    public static void idempotent(String bizType, Object bizId, String bizNo, String judgement) {
        log.info("{} stage=IDEMPOTENT bizType={} bizId={} bizNo={} judgement={}",
                TAG, bizType, bizId, bizNo, judgement);
    }

    /**
     * 第三段【执行结果】：记录动作的最终走向。
     *
     * 【注意】失败时用 {@code log.warn} 而非 {@code error}：
     * 业务性失败（余额不足、券已领完）是预期内的正常拒绝，用 ERROR 会污染告警；
     * 系统级故障由调用方在 catch 里自行打 ERROR。
     */
    public static void result(String bizType, Object bizId, boolean success, String detail) {
        if (success) {
            log.info("{} stage=RESULT bizType={} bizId={} success=true detail={}", TAG, bizType, bizId, detail);
        } else {
            log.warn("{} stage=RESULT bizType={} bizId={} success=false detail={}", TAG, bizType, bizId, detail);
        }
    }

    /**
     * 第四段【耗时】：记录从 {@code startMillis} 到现在的耗时。
     *
     * 【注意】必须在 finally 块或所有返回路径上调用，否则失败的请求没有耗时数据 ——
     * 而慢失败（例如下游超时）恰恰是最需要耗时的场景。
     */
    public static void cost(String bizType, Object bizId, long startMillis) {
        log.info("{} stage=COST bizType={} bizId={} costMs={}",
                TAG, bizType, bizId, System.currentTimeMillis() - startMillis);
    }

    /**
     * 便捷方法：入口 + 返回起始时间（无 detail）。
     * 用于调用方没有额外入参摘要可写的场景。
     */
    public static long enter(String bizType, Object bizId) {
        return enter(bizType, bizId, null);
    }
}
