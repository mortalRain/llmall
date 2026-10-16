-- =====================================================================
-- 01-undo-log-all.sql
-- 01 · 为所有业务库创建 Seata AT 模式必需的 undo_log 表
--
-- 执行：mysql -uroot -p < 01-undo-log-all.sql
--
-- ============================ 一、功能 ============================
-- 在 8 个业务库（llmall-user / llmall-shop / llmall-item / llmall-cart /
-- llmall-trade / llmall-pay / llmall-promotion / llmall-content）各建一张 undo_log，
-- 作为 Seata AT 模式全局回滚时的反向快照表。
--
-- ============================ 二、背景 ============================
-- Seata AT 的分支事务在本地库执行 DML 之前，必须把"改前镜像"写进本库的 undo_log；
-- 全局回滚时 TC 依据这张表做反向补偿。表缺失时分支注册/提交阶段直接报
-- "Table 'xxx.undo_log' doesn't exist"，全局事务无法提交 —— 跨服务扣库存、下单、
-- 发券整条链路一起失败。
--
-- ============================ 三、影响面 ============================
--   · 受影响：8 个库各新增一张 undo_log。CREATE TABLE IF NOT EXISTS，表已存在则整体跳过。
--   · 不受影响：任何既有表的结构与数据 —— 本脚本不含 ALTER / INSERT / UPDATE。
--   · 不受影响：llmall-search 库。llmall-search-service 未接入 Seata
--     （其 application.yaml 里没有 seata 配置），不参与全局事务，因此不需要这张表。
--     【注意】将来若给 search 开启全局事务，必须先补这张表 + 补 Seata 配置，
--     否则分支一注册就报 undo_log 不存在。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行（不可跳过）。8 个 USE 里任何一个库不存在，
--      都会出现"前几句成功、后面 ERROR 1049 Unknown database"的半成品状态。
--   2. 执行：mysql -uroot -p < 01-undo-log-all.sql
--   3. 核对（期望 8 行）：
--        SELECT TABLE_SCHEMA FROM information_schema.TABLES
--         WHERE TABLE_NAME = 'undo_log' AND TABLE_SCHEMA LIKE 'llmall-%'
--         ORDER BY TABLE_SCHEMA;
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行，第二遍不报错也不改结构。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 4 个新库 + seata 库）。
--   2) 各库自身的业务表由原商城脚本（上级目录 sql/llmall-*.sql）建立，与本脚本无先后要求。
--   3) 时机：部署 Seata 时执行本脚本；在打开跨服务全局事务（Seata AT 开关）之前必须已就绪。
--   4) 顺序反了会怎样：先跑本脚本再跑 00 → 除 llmall-trade 外逐句报 ERROR 1049；
--      本脚本漏跑就开启全局事务 → 全局事务必然回滚失败，且报错发生在运行期而非启动期，
--      表现为"偶发下单失败"，排查成本高于启动期报错。
--   5) 只有 llmall-item / llmall-trade / llmall-promotion 三个服务在 application.yaml 里
--      配了 seata（tx-service-group: llmall-tx-group）；另外 5 个库的 undo_log 是为后续
--      接入预留的，建了不影响当前运行。
--
-- ============================ 六、回滚方案 ============================
-- 逐库执行：DROP TABLE `llmall-user`.`undo_log`;    （其余 7 个库同理）
-- 【注意】undo_log 承载的是"进行中全局事务的回滚依据"，不是业务数据：
--   · 无进行中的全局事务 → 可直接 DROP，下次启用全局事务时重跑本脚本即可；
--   · 有未完成的全局事务 → DROP 后这些事务将无法回滚。回滚前先确认两处为空：
--       SELECT COUNT(*) FROM `llmall-user`.`undo_log`;          -- 期望 0
--       SELECT COUNT(*) FROM `seata`.`global_table`;            -- 期望 0
--   与 Seata 无关的部署可以直接 DROP，属可逆操作（重跑脚本即恢复）。
--
-- ============================ 附、风险等级 ============================
-- 低 —— 纯新增表（CREATE TABLE IF NOT EXISTS），可 DROP 完整回滚，不改动任何存量数据。
--       判据：改错的形态只有"漏建某个库"，后果是该库的全局事务报错（可发现、可恢复），
--       补跑一次本脚本即修复；不存在改写存量数据后无法自动还原的路径。
-- =====================================================================

USE `llmall-user`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id`     BIGINT       NOT NULL COMMENT 'branch transaction id',
  `xid`           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
  `context`       VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` LONGBLOB     NOT NULL COMMENT 'rollback info',
  `log_status`    INT          NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created`   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  `log_modified`  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-shop`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-item`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-cart`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-trade`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-pay`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-promotion`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-content`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';
