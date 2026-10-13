package com.llmall.content.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把"DB 已提交才允许发生"的动作（写 Redis 缓存、投递 MQ 事件）挪到**事务提交之后**执行。
 *
 * <h3>为什么不能直接在事务里写 Redis</h3>
 * Redis 不参与本地事务，也没有回滚钩子：事务内先写 Redis，之后 DB 因为任何原因回滚
 * （唯一键冲突未被捕获、外键/约束失败、Seata 分支回滚、连接被 kill……），
 * Redis 里那条记录就成了**永远无法回滚的脏数据** —— 表现为"DB 没有这条关注，粉丝数却多了 1"、
 * "取关失败但排行榜里人还在"，而且没有任何补偿机制能发现它。
 *
 * <h3>afterCommit 的边界（别把它当银弹）</h3>
 * 它只保证**时序**：DB 提交成功后才执行；不保证一定执行 —— 提交完成后、回调执行前进程崩溃，
 * 这次缓存更新就丢了。所以放在这里的 Redis 写必须都是"可重建的缓存"，权威数据一律以 DB 为准
 * （本模块的计数、集合都支持未命中回源 DB，正是为了兜住这个缺口）。
 */
@Slf4j
public final class TxAfterCommit {

    private TxAfterCommit() {
    }

    /**
     * 注册"提交后执行"的动作；当前没有事务时立即执行（语义与"已提交"一致）。
     *
     * @param label  仅用于日志定位（出问题时能一眼看出是哪个动作没同步上）
     * @param action 事务提交后要执行的动作
     */
    public static void run(String label, Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(label, action);
                }
            });
            log.debug("已注册 afterCommit 动作: {}", label);
        } else {
            // 没有事务（例如被非事务方法直接调用）：立即执行，否则这个动作永远不会发生
            runQuietly(label, action);
        }
    }

    /**
     * 回调里**必须**吞异常：这时 DB 已经提交，事务早成功了，
     * 如果让 Redis 抖动（连接超时、超时抛异常）冒泡出去，调用方会收到 500 并以为"关注失败"，
     * 于是去重试 —— 而 DB 里其实已经写成功了，用户看到的是"点了没反应/报错但其实已经关注"。
     * 正确做法：记 ERROR 让监控发现，接口照常成功；缓存缺失由"未命中回源 DB"自愈。
     */
    private static void runQuietly(String label, Runnable action) {
        try {
            action.run();
            log.debug("afterCommit 动作已执行: {}", label);
        } catch (Exception e) {
            log.error("afterCommit 动作执行失败（DB 已提交，缓存/事件需靠回源或对账兜底）: {}", label, e);
        }
    }
}
