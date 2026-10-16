-- =====================================================================
-- 05-shop-message-log.sql
-- 05 · 门店域本地消息表（llmall-shop.message_log）
--
-- 执行：mysql -uroot -p < 05-shop-message-log.sql
--
-- ============================ 一、功能 ============================
-- 在 llmall-shop 库建出本地消息表 message_log，作为门店域事件
-- （路由键 shop.hot.changed：门店热度/门店文档变更 → 搜索域同步索引）的可靠投递依据。
--
-- ============================ 二、背景 ============================
-- 门店域的「评价 → 门店热度 → 搜索索引」链路里，发布方是 ShopEventPublisher。
-- 这条链路的不变量是：事件必须与业务写同事务落库、提交之后才投递、投递失败进退避重试。
-- 若退回"直接在业务事务里 convertAndSend + 失败只 log.error"的写法，会产生两个
-- 不会报错、只会静默失效的后果：
--     ① 事务内投递：事务回滚了事件可能已经发出去（下游不该动却动了）；
--        事务提交了、进程在提交与投递之间挂掉，事件则永远发不出去（下游该动却没动）。
--     ② 投递失败只记一条 error 日志：没有重试、没有对账 —— MQ 抖一下就是
--        "评价存在、热度不变"，且永久断裂、无任何信号。
-- 做法与 llmall-trade 完全同构（那边已验证的范式：同事务落库 → 提交后投递 → 退避重试 → 三态对账）：
--   1) record()            —— 与业务写（shop 行的热度/销量更新）在同一个本地事务里落库：
--                             事务提交 ⇒ 消息一定存在，不丢；
--   2) dispatchAfterCommit —— 提交之后才真正投递：保证低延迟，且不会"回滚了还发消息"；
--   3) MessageRetryTask    —— 投递失败按指数退避重发（5s → 10s → …… 上限 10 分钟）；
--   4) ShopSearchReconcileTask —— 对账兜底：问 llmall-search-service
--                             "这条 shop.hot.changed 你到底处理了没有"（三态，未知不下结论）。
-- 【为什么每个服务在自己的库里各建一张同名表】本地消息表的"本地"指的就是同一个本地事务，
--   它必须和业务数据在同一个库、同一次 commit 里，才能拿到"业务成功 ⇔ 消息存在"这个保证。
--
-- ============================ 三、影响面 ============================
--   · 受影响：仅 llmall-shop 库新增一张 message_log（CREATE TABLE IF NOT EXISTS，
--     表已存在则整体跳过，不校验列差异、不补列）。
--   · 不受影响：llmall-shop 任何既有表的结构与数据（shop / shop_type / shop_stat 等）；
--     本脚本不含 ALTER / INSERT / UPDATE。
--   · 不受影响：其它库 —— llmall-trade.message_log 由 02-shared-infra.sql 建，
--     llmall-content.message_log 由 06-content-message-log.sql 建，三张表互不干涉。
--   · 不受影响：应用未发布"登记消息表"版本时，这张表只是空表，不产生任何行为变化。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认已经跑过 00-create-databases.sql 与 02-shared-infra.sql
--      （不可跳过：llmall-shop 库本身由 00 建；02 建的是 llmall-shop.consume_record，
--      本脚本建的是生产端 message_log，两者是 B4 可靠投递的生产端与消费端，缺一不可）。
--   2. 执行：mysql -uroot -p < 05-shop-message-log.sql
--   3. 核对（期望 1 行）：
--        SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES
--         WHERE TABLE_SCHEMA = 'llmall-shop' AND TABLE_NAME = 'message_log';
--   4. 核对幂等闸门存在（缺 uk_biz 时同一事件会被重复登记）：
--        SHOW INDEX FROM `llmall-shop`.`message_log`;    -- 期望有 uk_biz(biz_type,biz_id)
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行，第二遍不报错也不改结构。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 llmall-shop 库）。
--   2) 02-shared-infra.sql（消费端 consume_record 与 llmall-trade.message_log）。
--   3) 应用侧需已发布 ShopEventPublisher 走 messageLogService.record + dispatchAfterCommit
--      的版本；应用先发布、表后建 → 业务提交时 INSERT message_log 报表不存在，
--      会把"消息可能丢失"升级成"门店热度根本写不进去"。
--   4) 顺序反了会怎样：库不存在 → ERROR 1049；本脚本未跑就发布应用 → 上一条的后果。
--   5) 本文件已登记在 sql/merged/README.md 的执行清单里（第 5.6 步；第三节 file-by-file
--      清单也列了它），执行顺序以该 README 为准。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不写消息表"的版本后执行：
--   DROP TABLE `llmall-shop`.`message_log`;
-- 【不可逆】表里 status=0/3 的行（待发送、失败待重试）是这些门店变更事件唯一的重投依据：
--   DROP 会永久丢掉它们，搜索域的读模型将永远停在旧热度/旧销量，只能靠
--   POST /search/view/rebuild 全量重建来补齐。
--   回滚前先导出：
--     mysqldump -uroot -p llmall-shop message_log > backup-05-message-log.sql
--   若只想停掉重试而保留痕迹，可改为把待发送行标记完成，而不是 DROP 整表：
--     UPDATE `llmall-shop`.`message_log` SET `status` = 2 WHERE `status` IN (0, 3);
--
-- ============================ 附、风险等级 ============================
-- 中 —— 建表本身是纯新增（可 DROP），但表内容"未投递的门店变更事件"一旦丢失无法重建，
--       只能靠全量重建读模型补偿；判据：改错后不能自动回到原状（需要重放/重建），
--       故为中而不是低；又不涉及资金与账号，不到高。
-- =====================================================================

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
