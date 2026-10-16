-- =====================================================================
-- 06-content-message-log.sql
-- 06 · 内容域本地消息表（llmall-content.message_log）
--
-- 执行：mysql -uroot -p < 06-content-message-log.sql
--
-- ============================ 一、功能 ============================
-- 在 llmall-content 库建出本地消息表 message_log，作为内容域事件
-- （评价发布 → review.created → 门店热度）生产端的可靠投递依据。
--
-- ============================ 二、背景 ============================
-- 内容域的「评价发布 → review.created → 门店热度」这一跳，发布方是
-- ContentEventPublisher#publishReviewCreated，其不变量是：
-- 事件必须与业务写同事务登记、提交之后才投递、投递失败记 FAILED + 退避时间由 MessageRetryTask 重发。
-- 若退回"提交后直接 convertAndSend + 失败只 log.error"的写法，时序虽然没错
-- （调用方套了 TxAfterCommit，不会事务内直发），但可靠性是零 ——
-- 提交成功之后、回调执行之前进程崩溃 / MQ 抖动 / 网络超时，这条评价就再也不会通知到门店域。
-- 症状是"评价存在、门店热度不变"，而且永久断裂、无任何信号（没有重试、没有对账、没有告警）。
-- 做法与 llmall-shop / llmall-trade 的同名表完全同构（那边已验证的范式）：
--   1) record()            —— 与业务写（blog 行 + feed_inbox）在同一个本地事务里落库：
--                             事务提交 ⇒ 消息一定存在，不丢；
--   2) dispatchAfterCommit —— 提交之后才真正投递：保证低延迟，且不会"回滚了还发消息"；
--   3) MessageRetryTask    —— 投递失败按指数退避重发（5s → 10s → …… 上限 10 分钟），
--                             失败原因与重试次数写回本表；
--   4) 对账                 —— 内容域未实现，因此 status=2（下游确认已处理）暂无写入方；
--                             列定义与取值枚举仍与其它域保持一致，将来补对账任务时不需要改表。
-- 【为什么每个服务在自己的库里各建一张同名表】本地消息表的"本地"指的就是同一个本地事务，
--   它必须和业务数据在同一个库、同一次 commit 里，才能拿到"业务成功 ⇔ 消息存在"这个保证。
--
-- ============================ 三、影响面 ============================
--   · 受影响：仅 llmall-content 库新增一张 message_log（CREATE TABLE IF NOT EXISTS，
--     表已存在则整体跳过，不校验列差异、不补列）。
--   · 不受影响：llmall-content 任何既有表的结构与数据（blog / blog_comments / blog_like /
--     follow / feed_inbox）；本脚本不含 ALTER / INSERT / UPDATE。
--   · 不受影响：其它库 —— llmall-trade.message_log 由 02-shared-infra.sql 建，
--     llmall-shop.message_log 由 05-shop-message-log.sql 建，三张表互不干涉。
--   · 不受影响：应用未发布"登记消息表"版本时，这张表只是空表，不产生任何行为变化。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认已经跑过 00-create-databases.sql 与 30-content.sql
--      （不可跳过：llmall-content 库由 00 建；本表虽不依赖 blog 表，但内容域的业务写与
--      消息登记在同一个事务里，blog 表没建好时应用写评价本身就会失败）。
--   2. 执行：mysql -uroot -p < 06-content-message-log.sql
--   3. 核对（期望 1 行）：
--        SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES
--         WHERE TABLE_SCHEMA = 'llmall-content' AND TABLE_NAME = 'message_log';
--   4. 核对幂等闸门存在（缺 uk_biz 时同一条评价会被重复登记、下游重复累加热度）：
--        SHOW INDEX FROM `llmall-content`.`message_log`;    -- 期望有 uk_biz(biz_type,biz_id)
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行，第二遍不报错也不改结构。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 llmall-content 库）。
--   2) 30-content.sql（内容域业务表）；02-shared-infra.sql（llmall-shop.consume_record，
--      即本表消息的消费端幂等闸门）。
--   3) 应用侧需已发布 ContentEventPublisher 走 messageLogService.record +
--      dispatchAfterCommit 的版本；应用先发布、表后建 → 发布评价时 INSERT message_log
--      报表不存在，评价直接发不出去。
--   4) 顺序反了会怎样：库不存在 → ERROR 1049；本脚本未跑就发布应用 → 上一条的后果；
--      消费端 consume_record（02）未建 → 门店域消费 review.created 时无法判重，
--      热度会被重复累加（数字只会变大，不会报错）。
--   5) 本文件已登记在 sql/merged/README.md 的执行清单里（第 5.7 步；第三节 file-by-file
--      清单也列了它），执行顺序以该 README 为准。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不写消息表"的版本后执行：
--   DROP TABLE `llmall-content`.`message_log`;
-- 【不可逆】表里 status=0/3 的行（待发送、失败待重试）是这些 review.created 事件
--   唯一的重投依据：DROP 会永久丢掉它们，门店热度将永远少算这些评价，
--   只能靠重新触发热度重算（或全量重建）来补齐，无法从本表恢复。
--   回滚前先导出：
--     mysqldump -uroot -p llmall-content message_log > backup-06-message-log.sql
--   若只想停掉重试而保留痕迹，可改为把待发送行标记完成，而不是 DROP 整表：
--     UPDATE `llmall-content`.`message_log` SET `status` = 2 WHERE `status` IN (0, 3);
--
-- ============================ 附、风险等级 ============================
-- 中 —— 建表本身是纯新增（可 DROP），但表内容"未投递的评价事件"一旦丢失无法重建，
--       只能靠重算热度补偿；判据：改错后不能自动回到原状（需要重放/重算），
--       故为中而不是低；又不涉及资金与账号，不到高。
-- =====================================================================

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
