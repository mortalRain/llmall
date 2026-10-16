-- =====================================================================
-- 03b-seata-server-1.5.2-upgrade.sql
-- 03b · Seata 1.4.2 → 1.5.2 会话表升级（seata 库）
--
-- DDL 逐字取自 Seata 官方发行包 script/server/db/mysql.sql（v1.5.2 标签，经 Gitee 镜像取得并逐条比对）：
--   https://gitee.com/seata-io/seata/raw/v1.5.2/script/server/db/mysql.sql
--
-- ============================ 一、功能 ============================
-- 把按 1.4.2 建出来的 seata 会话表就地升级成 1.5.2 形态：
--   ① lock_table 补 `status` 列（0:locked / 1:rollbacking）+ idx_status、idx_xid_and_branch_id 两个索引；
--   ② 新增第 4 张表 distributed_lock 并补 4 行种子
--      （AsyncCommitting / RetryCommitting / RetryRollbacking / TxTimeoutCheck）；
--   ③ global_table 的索引名由 idx_gmt_modified_status 改为 idx_status_gmt_modified；
--   ④ 三张表字符集统一 utf8mb4。
--
-- ============================ 二、背景 ============================
-- 1.5.x 与 1.4.x 的 store.mode=db 表结构不同，直接换镜像会出现两类故障：
--   · 启动/运行期报列或表不存在：缺 `status` 列时 lock_table 的加锁与回滚态写入失败；
--   · 缺 distributed_lock 的 4 行种子时，服务端的异步提交、重试提交、重试回滚、超时检查
--     四个定时任务拿不到分布式锁 —— 表现为"事务迟迟不提交、不重试、不超时清理"，
--     没有明确报错，只会让悬挂事务越积越多。
--
-- ============================ 三、影响面 ============================
--   · 受影响：仅 seata 库的 lock_table / global_table / branch_table 与新建的 distributed_lock。
--     其中 ALTER 只新增列与索引，不改写已有列的数据；CONVERT TO CHARACTER SET 会重建三张表。
--   · 不受影响：llmall-* 业务库与其中的数据 —— 本脚本只操作 seata 库。
--   · 不受影响：global_table / branch_table 的行数据（字符集转换不改行内容）；
--     lock_table 已有行的 status 列会被补为默认值 '0'（locked），这正是 1.4.2 的语义。
--   · 【注意】CONVERT TO CHARACTER SET 会锁表并重建，执行期间 seata-server 的读写会阻塞：
--     三张表数据量极小（会话期数据），转换成本可忽略，但仍应在没有大批量事务的窗口执行。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行、且 seata 库里是 1.4.2 的表结构。
--   2. 停掉 seata-server（不可跳过：运行中转换字符集会阻塞，且 1.5.2 与 1.4.2 的 Server
--      不能同时对着同一批表跑）。
--   3. 执行（两种写法都可以，脚本里已带 USE `seata`;）：
--        mysql -uroot -p123 seata < 03b-seata-server-1.5.2-upgrade.sql
--        mysql -uroot -p < 03b-seata-server-1.5.2-upgrade.sql
--      【注意】`USE `seata`;` 不能省：下面的守卫用 DATABASE() 取当前库，
--      取不到库时守卫会判成"不存在"并去 ALTER —— 报错信息会指向表不存在而不是库未选，更难排查。
--   4. 复验：脚本末尾"⑥ 复验"三句（期望 4 张表、lock_table 含 status、distributed_lock 4 行），
--      取消注释后执行。
--   5. 换 1.5.2 镜像重启 seata-server。
-- 幂等：本脚本按"先判断再执行"写成，可重复执行 —— 第二遍全部走"已存在，跳过"分支，
--   不报错也不改结构；distributed_lock 的种子用 INSERT IGNORE。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql；2) 库里已有 1.4.2 的 global_table / branch_table / lock_table。
--   3) 与 03 的关系：全新安装直接跑 03-seata-server.sql（已含 1.5.2 全部 4 张表），
--      不要再跑本脚本；从 1.4.2 升级则按 README 第 4 步"先 03 再 03b"连着跑。
--   4) 顺序反了会怎样：
--      · 全新空库只跑本脚本 → global_table / branch_table 不存在，④ 的 CREATE 会建出
--        distributed_lock，但 ①②③ 的守卫判定后执行的 ALTER 全部报表不存在；
--      · 先换 1.5.2 镜像再跑本脚本 → 服务端启动即报缺列/缺表，属于"必须停机升级"的原因。
--
-- ============================ 六、回滚方案 ============================
-- 【不可逆（对 1.4.2 而言）】本脚本没有对应的降级脚本：
--   · lock_table 新增的 column 与 index 只能手工 DROP COLUMN / DROP KEY 还原；
--   · global_table 的索引改名（③）与 ④ 的字符集转换无法自动回退到 1.4.2 的原始定义；
--   · distributed_lock 可直接 DROP TABLE（1.4.2 不需要它）。
--   回滚到 1.4.2 的正确做法是"从升级前的备份恢复 seata 库"，因此执行前必须备份（不可跳过）：
--     mysqldump -uroot -p --databases seata > backup-seata-before-1.5.2.sql
--   注意 seata 库里全是会话期数据：真正不可丢的不是这些表，而是"升级期间不要有进行中的
--   全局事务"—— 升级前请确认 global_table 已清空。
--
-- ============================ 附、风险等级 ============================
-- 中 —— 会就地改写 seata 的存量表结构（新增列/索引、索引改名、字符集转换），
--       但都是幂等守卫 + 可备份还原，且不触碰业务库；判据：改错之后无法"用脚本自动回退"，
--       只能从备份恢复，所以定中而不是低；又因为不涉及业务数据与资金，不到高。
-- =====================================================================
USE `seata`;

-- ① lock_table：补 status 列
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lock_table' AND COLUMN_NAME = 'status');
SET @s := IF(@c = 0,
    'ALTER TABLE `lock_table` ADD COLUMN `status` TINYINT NOT NULL DEFAULT ''0'' COMMENT ''0:locked ,1:rollbacking'' AFTER `pk`',
    'SELECT ''lock_table.status 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ② lock_table：补两个索引
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lock_table' AND INDEX_NAME = 'idx_status');
SET @s := IF(@c = 0, 'ALTER TABLE `lock_table` ADD KEY `idx_status` (`status`)', 'SELECT ''idx_status 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lock_table' AND INDEX_NAME = 'idx_xid_and_branch_id');
SET @s := IF(@c = 0, 'ALTER TABLE `lock_table` ADD KEY `idx_xid_and_branch_id` (`xid`,`branch_id`)', 'SELECT ''idx_xid_and_branch_id 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ③ global_table：索引改名（功能等价，保持与官方 1.5.2 schema 一致）
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'global_table' AND INDEX_NAME = 'idx_gmt_modified_status');
SET @s := IF(@c > 0,
    'ALTER TABLE `global_table` DROP KEY `idx_gmt_modified_status`, ADD KEY `idx_status_gmt_modified` (`status`,`gmt_modified`)',
    'SELECT ''global_table 索引已是 1.5.2 形态，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ④ 新增 distributed_lock（1.5.x 定时任务的分布式锁），并补 4 行种子（可重复执行）
CREATE TABLE IF NOT EXISTS `distributed_lock`
(
    `lock_key`   CHAR(20)    NOT NULL,
    `lock_value` VARCHAR(20) NOT NULL,
    `expire`     BIGINT,
    PRIMARY KEY (`lock_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('AsyncCommitting', ' ', 0);
INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('RetryCommitting', ' ', 0);
INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('RetryRollbacking', ' ', 0);
INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('TxTimeoutCheck', ' ', 0);

-- ⑤ 字符集统一 utf8mb4（与官方 1.5.2 DDL 一致；三张表数据量极小，转换成本可忽略）
ALTER TABLE `global_table` CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE `branch_table` CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE `lock_table` CONVERT TO CHARACTER SET utf8mb4;

-- ⑥ 复验（期望 4 张表、lock_table 含 status、distributed_lock 4 行）
-- SELECT table_name FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE();
-- SELECT column_name FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='lock_table';
-- SELECT * FROM distributed_lock;
