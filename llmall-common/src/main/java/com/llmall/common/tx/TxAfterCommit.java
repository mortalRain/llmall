package com.llmall.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把"DB 已提交才允许发生"的动作（写 Redis 缓存、投递 MQ 事件）放到事务提交之后执行。
 *
 * 【注意】只保证时序，不保证一定执行：Redis 不参与本地事务、没有回滚钩子，事务内先写 Redis
 * 而 DB 随后回滚，就会留下无法回滚的脏数据；提交完成、回调执行前进程崩溃，这次更新同样会丢。
 * 因此这里只允许放可重建的缓存，权威数据一律以 DB 为准（未命中时回源 DB）。
 *
 * 【注意】只适用于"提交后执行"的语义。需要区分提交与回滚（例如回滚补偿）的场景必须自己实现
 * {@code afterCompletion}，用它会把回滚路径也当成提交处理。
 */
@Slf4j
public final class TxAfterCommit {

    private TxAfterCommit() {
    }

    /**
     * 注册"提交后执行"的动作；当前没有事务上下文时降级为立即执行（语义与"已提交"一致）。
     * label 仅用于日志定位。
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
            // 无事务上下文（例如被非事务方法直接调用）：立即执行，否则该动作永远不会发生
            runQuietly(label, action);
        }
    }

    /**
     * 【注意】回调里必须吞异常：此时 DB 已提交，让 Redis 抖动冒泡出去会让调用方以为业务失败而重试，
     * 而 DB 其实已经写成功。记 ERROR 交给监控、接口照常成功，缓存缺失由未命中回源 DB 自愈。
     *
     * 【注意】因此禁止把"失败必须让调用方看见"的动作放进来（例如锁券失败要抛业务异常）——
     * 那类动作需要的是传播异常，与这里的语义相反。
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
