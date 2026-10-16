-- =====================================================================
-- 02-shared-infra.sql
-- 02 · 最终一致性基础设施表
--
-- 执行：mysql -uroot -p < 02-shared-infra.sql
--
-- ============================ 一、功能 ============================
-- 建两类"可靠消息最终一致性"基础设施表：
--   · message_log    —— 本地消息表（生产端）：与业务写同一个本地事务落库，
--                       保证"业务成功 → 消息一定存在 → 一定会被投递"。
--   · consume_record —— 消费幂等表（消费端）：靠唯一索引挡住重复投递
--                       （MQ 是 at-least-once，不保证不重复）。
--
-- ============================ 二、背景 ============================
-- 直接用 convertAndSend 发事件有两个不会报错、只会静默失效的缺口，必须用这两张表堵：
--   ① 事务内投递：事务回滚了消息可能已经发出去（下游不该动却动了）；事务提交了、
--      进程在"提交"与"投递"之间挂掉，消息则永远发不出去（下游该动却没动）。
--   ② 投递/消费失败只记一条 error 日志：没有重试、没有对账 —— MQ 抖一下就是
--      "订单已支付、积分没加"，且永久断裂、无任何信号。
-- 因此生产端用 message_log 做到"同事务落库 + 提交后投递 + 退避重试 + 三态对账"，
-- 消费端用 consume_record 的唯一键做到"同一条消息只处理一次"。
--
-- ============================ 三、影响面 ============================
--   · 受影响（新增表，共 6 张）：
--       llmall-trade.message_log、llmall-trade.consume_record
--       llmall-user.consume_record、llmall-shop.consume_record
--       llmall-promotion.consume_record、llmall-search.consume_record
--   · 不受影响：任何既有表结构与数据 —— 本脚本不含 ALTER / INSERT / UPDATE。
--   · 不受影响：llmall-item / llmall-cart / llmall-pay / llmall-content 四个库 ——
--     content 域的生产端消息表由 06-content-message-log.sql 单独建（结构与 trade 同构）；
--     这四个库当前没有任何消费者写 consume_record，故不建（建了是空表，纯占位）。
--   · 不受影响：门店域的生产端消息表由 05-shop-message-log.sql 建，不在本脚本内。
--   【为什么每个服务在自己的库里各建一张同名表】本地消息表的"本地"指的就是同一个本地事务，
--   它必须与业务数据同库、同一次 commit，才能拿到"业务成功 ⇔ 消息存在"这个保证；
--   放到公共库就等于跨库，反而制造新的分布式事务。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行（不可跳过）。脚本里有 5 处 USE，
--      库不存在会中途报 ERROR 1049，留下"前几个库有表、后面没有"的半成品。
--   2. 执行：mysql -uroot -p < 02-shared-infra.sql
--   3. 核对（期望 6 行）：
--        SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES
--         WHERE TABLE_NAME IN ('message_log','consume_record')
--           AND TABLE_SCHEMA LIKE 'llmall-%' ORDER BY TABLE_SCHEMA, TABLE_NAME;
--   4. 核对幂等闸门确实存在（唯一键缺失时重复消费不会被拦住）：
--        SHOW INDEX FROM `llmall-trade`.`message_log`;      -- 期望有 uk_biz
--        SHOW INDEX FROM `llmall-trade`.`consume_record`;   -- 期望有 uk_consume
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql。
--   2) 应用侧需已发布"记账 + 重试 + 对账"版本，否则表建好后不会有数据写入。
--   3) 顺序反了会怎样：
--      · 先于 00 执行 → 逐句 ERROR 1049；
--      · 先发布应用、后建表 → 业务提交时 INSERT message_log 直接报表不存在，
--        把"消息丢失"升级成"业务写不进去"，影响面比不建表更大，因此本脚本必须先于应用发布。
--   4) 订单库沿用 llmall-trade（设计文档里的 llmall-order 只是命名建议，不存在该库）。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不写消息表/不读幂等表"的版本后，逐库执行：
--   DROP TABLE `llmall-trade`.`message_log`;
--   DROP TABLE `llmall-trade`.`consume_record`;
--   DROP TABLE `llmall-user`.`consume_record`;      -- shop / promotion / search 同理
-- 【不可逆】message_log 里"待发送/失败待重试"的行是唯一的重投依据：
--   DROP 会永久丢掉这些未投递的消息，相关业务（积分、热度）再也不会补上，
--   只能靠人工比对源库修数。回滚前必须先导出：
--     mysqldump -uroot -p llmall-trade message_log consume_record > backup-02.sql
--   consume_record 丢的是幂等记录：DROP 后已消费过的消息若被重投，会被重复处理一次
--   （积分多加、热度多算），因此回滚必须在"下游确认无重投"的窗口内进行。
--
-- ============================ 附、风险等级 ============================
-- 中 —— 均为纯新增表（可 DROP 回滚），但表里会积累"未投递消息/幂等记录"这类
--       一旦丢失无法重建的数据；判据：改错（漏建、DROP）不会破坏存量业务表，
--       可通过对源库重放与导出备份恢复，但恢复过程需要人工判断，不能自动回到原状。
-- =====================================================================
USE `llmall-trade`;

-- 本地消息表（每个"消息生产者"服务在自己的库里建自己的消息表）
CREATE TABLE IF NOT EXISTS `message_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型，如 ORDER_CREATED / ORDER_PAID / REVIEW_CREATED',
  `biz_id`        VARCHAR(64)  NOT NULL COMMENT '业务主键（订单号、评价 id 等）',
  `exchange`      VARCHAR(128) NOT NULL COMMENT '目标交换机',
  `routing_key`   VARCHAR(128) NOT NULL COMMENT '路由键',
  `payload`       TEXT         NOT NULL COMMENT '消息体（JSON）',
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

-- 消费幂等表（每个"消息消费者"服务在自己的库里建一份）
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `biz_type`    VARCHAR(64) NOT NULL COMMENT '业务类型',
  `biz_id`      VARCHAR(64) NOT NULL COMMENT '业务主键',
  `consumer`    VARCHAR(64) NOT NULL COMMENT '消费方标识（服务名）',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`) COMMENT '唯一索引就是幂等闸门'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';

-- 其余消费者服务各自建一份（同一段 DDL，换库执行即可）
USE `llmall-user`;
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `biz_type` VARCHAR(64) NOT NULL, `biz_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(64) NOT NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';

USE `llmall-shop`;
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `biz_type` VARCHAR(64) NOT NULL, `biz_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(64) NOT NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';

USE `llmall-promotion`;
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `biz_type` VARCHAR(64) NOT NULL, `biz_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(64) NOT NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';

USE `llmall-search`;
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `biz_type` VARCHAR(64) NOT NULL, `biz_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(64) NOT NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';
