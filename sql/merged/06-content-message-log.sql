-- ============================================================
-- 06 · 内容域本地消息表（llmall-content.message_log）
--
-- 为什么新增这张表（审计 B4 · 生产端）：
--   内容域的「评价发布 → review.created → 门店热度」这一跳，发布方
--   ContentEventPublisher#publishReviewCreated 原实现是"提交后 convertAndSend + 失败只 log.error"：
--   时序本身没错（调用方套了 TxAfterCommit，不会事务内直发），但可靠性是零 ——
--   提交成功之后、回调执行之前进程崩溃 / MQ 抖动 / 网络超时，这条评价就**再也不会**通知到门店域。
--   症状是"评价存在、门店热度不变"，而且永久断裂、无任何信号（没有重试、没有对账、没有告警）。
--
-- 做法与 llmall-shop / llmall-trade 的同名表完全同构（那边已验证的范式）：
--   1) record()            —— 与业务写（blog 行 + feed_inbox）在**同一个本地事务**里落库：
--                             事务提交 ⇒ 消息一定存在，不丢；
--   2) dispatchAfterCommit —— 提交之后才真正投递：保证低延迟，且不会"回滚了还发消息"；
--   3) MessageRetryTask    —— 投递失败按指数退避重发（5s → 10s → …… 上限 10 分钟），
--                             失败原因与重试次数写回本表；
--   4) 对账                 —— 内容域本轮**未实现**（审计 B4 只要求生产端前三段）。
--                             status=2（DONE）因此暂无写入方，结构上先与其它域保持一致。
--
-- 为什么每个服务在自己的库里各建一张同名表：本地消息表的"本地"指的就是**同一个本地事务**，
--   它必须和业务数据在同一个库、同一次 commit 里，才能拿到"业务成功 ⇔ 消息存在"这个保证。
--
-- 幂等：脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- 说明：本文件是本次改动新增的（sql/merged/README.md 的清单里没有它 —— 本次任务不允许改那个文件）。
-- ============================================================

USE `llmall-content`;

-- 本地消息表（与 llmall-shop.message_log / llmall-trade.message_log 结构一致，便于各域用同一套运维/排查口径）
CREATE TABLE IF NOT EXISTS `message_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型，内容域目前只有 REVIEW_CREATED',
  `biz_id`        VARCHAR(64)  NOT NULL COMMENT '业务主键（评价 id = blogId，与 ReviewCreatedEvent.bizId 一致，也是下游 consume_record 的幂等键）',
  `exchange`      VARCHAR(128) NOT NULL COMMENT '目标交换机',
  `routing_key`   VARCHAR(128) NOT NULL COMMENT '路由键',
  `payload`       TEXT         NOT NULL COMMENT '消息体（JSON，ReviewCreatedEvent 全字段）',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待发送 1 已投递 2 已完成(下游确认已处理) 3 失败待重试',
  `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '已重试次数（指数退避）',
  `next_retry_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
  `error_msg`     VARCHAR(1024) DEFAULT NULL COMMENT '最近一次失败原因',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`),
  KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表（可靠消息最终一致性）';
