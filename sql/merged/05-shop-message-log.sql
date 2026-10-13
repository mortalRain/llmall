-- ============================================================
-- 05 · 门店域本地消息表（llmall-shop.message_log）
--
-- 为什么新增这张表（审计 B4）：
--   门店域的「评价 → 门店热度 → 搜索索引」链路里，发布方（ShopEventPublisher）原实现是
--   "直接在业务事务里 convertAndSend + 失败只 log.error"。它的两个后果都不会报错、只会静默失效：
--     ① 事务内投递：事务回滚了事件可能已经发出去（下游不该动却动了）；
--        事务提交了、进程在提交与投递之间挂掉，事件则永远发不出去（下游该动却没动）。
--     ② 投递失败只记一条 error 日志：没有重试、没有对账 —— MQ 抖一下就是
--        "评价存在、热度不变"，且**永久断裂、无任何信号**。
--
-- 做法与 llmall-trade 完全同构（那边已验证的范式：同事务落库 → 提交后投递 → 退避重试 → 三态对账）：
--   1) record()            —— 与业务写（shop 行的热度/销量更新）在**同一个本地事务**里落库：
--                             事务提交 ⇒ 消息一定存在，不丢；
--   2) dispatchAfterCommit —— 提交之后才真正投递：保证低延迟，且不会"回滚了还发消息"；
--   3) MessageRetryTask    —— 投递失败按指数退避重发（5s → 10s → …… 上限 10 分钟）；
--   4) ShopSearchReconcileTask —— 对账兜底：问 llmall-search-service
--                             "这条 shop.hot.changed 你到底处理了没有"（三态，未知不下结论）。
--
-- 为什么每个服务在自己的库里各建一张同名表：本地消息表的"本地"指的就是**同一个本地事务**，
--   它必须和业务数据在同一个库、同一次 commit 里，才能拿到"业务成功 ⇔ 消息存在"这个保证。
--
-- 幂等：脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- 说明：本文件是本次改动新增的（sql/merged/README.md 的清单里没有它 —— 本次任务不允许改那个文件）。
-- ============================================================

USE `llmall-shop`;

-- 本地消息表（与 llmall-trade.message_log 结构一致，便于两域用同一套运维/排查口径）
CREATE TABLE IF NOT EXISTS `message_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型，门店域目前只有 SHOP_HOT_CHANGED',
  `biz_id`        VARCHAR(64)  NOT NULL COMMENT '业务主键（门店 id + 事件时间戳，一次热度变更一条）',
  `exchange`      VARCHAR(128) NOT NULL COMMENT '目标交换机',
  `routing_key`   VARCHAR(128) NOT NULL COMMENT '路由键',
  `payload`       TEXT         NOT NULL COMMENT '消息体（JSON，整行门店快照）',
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
