-- =====================================================================
-- 03-seata-server.sql
-- 03 · Seata Server 会话表（seata 库）· 1.5.2 版
--
-- ============================ 一、功能 ============================
-- 在 seata 库建出 Seata Server 以 store.mode=db 运行所需的 4 张表
-- （global_table / branch_table / lock_table / distributed_lock），
-- 并为 distributed_lock 补 4 行定时任务种子数据。
--
-- DDL 逐字取自 Seata 官方发行包 script/server/db/mysql.sql（v1.5.2 标签，经 Gitee 镜像取得），
-- 与上游逐条比对过；升级 Seata 版本时应对着新版本的同名文件重新比对，不要凭记忆改列：
--   https://gitee.com/seata-io/seata/raw/v1.5.2/script/server/db/mysql.sql
--
-- ============================ 二、背景 ============================
-- seata-server 的 store.mode=db 必须有这些表，否则服务起不来（启动期即报表不存在）。
-- 之所以把官方 DDL 收进本目录而不是让使用者自己去发行包里找：docker 镜像里不含发行包的
-- script 目录（1.4.2 与 1.5.2 镜像实测 find 不到），按官方文档"执行 script/server/db/mysql.sql"
-- 在容器化部署下根本拿不到文件。
-- 1.5.2 与 1.4.2 的表结构差异（从 1.4.2 升级过来的库请改用 03b 脚本，不要重跑本文件）：
--   ① lock_table 多了 `status` 列与 idx_status / idx_xid_and_branch_id 两个索引；
--   ② 多了第 4 张表 `distributed_lock` 且必须有 4 行种子（服务端定时任务抢锁用）；
--   ③ global_table 索引名改为 idx_status_gmt_modified；
--   ④ 字符集统一 utf8mb4。
--
-- ============================ 三、影响面 ============================
--   · 受影响：仅 seata 库。4 张表全部 CREATE TABLE IF NOT EXISTS，已存在则整体跳过。
--   · 不受影响：llmall-* 业务库与其中的数据 —— 本脚本只操作 seata 库。
--   · 不受影响：Seata 已有的全局会话数据。已存在的表不会被 ALTER、不会被清空；
--     种子行用 INSERT IGNORE，已存在的 4 行不会重复插入、也不会被改写。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行（不可跳过：脚本依赖 seata 库，
--      库不存在时报 ERROR 1049 Unknown database，且下面的 USE 也不能省）。
--   2. 执行（两种写法都可以，脚本里已带 USE `seata`;）：
--        mysql -uroot -p123 seata < 03-seata-server.sql
--        mysql -uroot -p < 03-seata-server.sql
--      【注意】`USE `seata`;` 这行不能删：删掉后不带库名的第二种写法会报
--      "ERROR 1046 (3D000): No database selected"。
--   3. 核对（期望 4 行）：
--        SELECT table_name FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'seata';
--        SELECT lock_key FROM `seata`.`distributed_lock`;   -- 期望 4 行
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE，可重复执行。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 seata 库）；2) 一个可用的 MySQL 8 实例。
--   3) 全新安装：本脚本；从 1.4.2 升级：03b-seata-server-1.5.2-upgrade.sql。
--      README 第 4 步把两个脚本连着跑（先 03 建全量表、再 03b 补增量），两步都要跑：
--      只跑 03 而库里已有 1.4.2 的表 → 存量表不会被改写，lock_table 仍缺 status 列；
--      只跑 03b 而全新库 → branch_table / global_table 根本不存在，守卫里的 ALTER 全报错。
--   4) 顺序反了会怎样：seata 库未建 → ERROR 1049；应用先启动 → 连接 Seata Server 失败，
--      因为 Server 自己都没起来（store.mode=db 依赖这些表）。
--
-- ============================ 六、回滚方案 ============================
-- 停止 seata-server 后执行：
--   DROP TABLE `seata`.`distributed_lock`;
--   DROP TABLE `seata`.`lock_table`;
--   DROP TABLE `seata`.`branch_table`;
--   DROP TABLE `seata`.`global_table`;
-- 【注意】这四张表是 Seata Server 的运行期状态，不是业务数据：
--   · 表里若有未完成的全局事务（行未清空），DROP 后这些事务永久无法提交或回滚，
--     对应的业务侧分支（已扣的库存、已发的券）只能人工核对修复。
--   · 回滚前先确认没有悬挂会话：SELECT COUNT(*) FROM `seata`.`global_table`;   -- 期望 0
--   · 全部清空后 DROP 属可逆操作（重跑本脚本即恢复），不涉及业务库。
--
-- ============================ 附、风险等级 ============================
-- 低 —— 全部是 seata 库内的新建表（IF NOT EXISTS + INSERT IGNORE），不触碰任何业务库、
--       不改写存量数据；判据：无论重复执行还是误删，只要当时没有进行中的全局事务，
--       重跑本脚本即可回到原状，属可自动恢复。
-- =====================================================================
USE `seata`;

-- -------------------------------- The script used when storeMode is 'db' --------------------------------
-- the table to store GlobalSession data
CREATE TABLE IF NOT EXISTS `global_table`
(
    `xid`                       VARCHAR(128) NOT NULL,
    `transaction_id`            BIGINT,
    `status`                    TINYINT      NOT NULL,
    `application_id`            VARCHAR(32),
    `transaction_service_group` VARCHAR(32),
    `transaction_name`          VARCHAR(128),
    `timeout`                   INT,
    `begin_time`                BIGINT,
    `application_data`          VARCHAR(2000),
    `gmt_create`                DATETIME,
    `gmt_modified`              DATETIME,
    PRIMARY KEY (`xid`),
    KEY `idx_status_gmt_modified` (`status`, `gmt_modified`),
    KEY `idx_transaction_id` (`transaction_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- the table to store BranchSession data
CREATE TABLE IF NOT EXISTS `branch_table`
(
    `branch_id`         BIGINT       NOT NULL,
    `xid`               VARCHAR(128) NOT NULL,
    `transaction_id`    BIGINT,
    `resource_group_id` VARCHAR(32),
    `resource_id`       VARCHAR(256),
    `branch_type`       VARCHAR(8),
    `status`            TINYINT,
    `client_id`         VARCHAR(64),
    `application_data`  VARCHAR(2000),
    `gmt_create`        DATETIME(6),
    `gmt_modified`      DATETIME(6),
    PRIMARY KEY (`branch_id`),
    KEY `idx_xid` (`xid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- the table to store lock data
CREATE TABLE IF NOT EXISTS `lock_table`
(
    `row_key`        VARCHAR(128) NOT NULL,
    `xid`            VARCHAR(128),
    `transaction_id` BIGINT,
    `branch_id`      BIGINT       NOT NULL,
    `resource_id`    VARCHAR(256),
    `table_name`     VARCHAR(32),
    `pk`             VARCHAR(36),
    `status`         TINYINT      NOT NULL DEFAULT '0' COMMENT '0:locked ,1:rollbacking',
    `gmt_create`     DATETIME,
    `gmt_modified`   DATETIME,
    PRIMARY KEY (`row_key`),
    KEY `idx_status` (`status`),
    KEY `idx_branch_id` (`branch_id`),
    KEY `idx_xid_and_branch_id` (`xid`, `branch_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 1.5.x 新增：服务端定时任务（异步提交/重试提交/重试回滚/超时检查）的分布式锁
CREATE TABLE IF NOT EXISTS `distributed_lock`
(
    `lock_key`   CHAR(20)    NOT NULL,
    `lock_value` VARCHAR(20) NOT NULL,
    `expire`     BIGINT,
    PRIMARY KEY (`lock_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 官方用普通 INSERT；这里改 INSERT IGNORE 使其可重复执行
INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('AsyncCommitting', ' ', 0);
INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('RetryCommitting', ' ', 0);
INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('RetryRollbacking', ' ', 0);
INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('TxTimeoutCheck', ' ', 0);
