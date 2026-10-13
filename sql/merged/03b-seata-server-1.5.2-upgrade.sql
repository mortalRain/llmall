-- ============================================================
-- 03b · Seata 1.4.2 → 1.5.2 会话表升级（seata 库）
-- 来源：官方 script/server/db/mysql.sql（v1.5.2 标签，经 Gitee 镜像取得并逐条比对）
--   https://gitee.com/seata-io/seata/raw/v1.5.2/script/server/db/mysql.sql
--
-- 为什么必须有这个脚本：1.5.x 的 store.mode=db 与 1.4.x **表结构不同**，
--   直接换镜像会启动失败或运行期报错：
--     ① lock_table 新增 `status` 列（0:locked / 1:rollbacking）+ 两个索引；
--     ② 新增第 4 张表 `distributed_lock`，且**必须插 4 行种子**
--        （AsyncCommitting / RetryCommitting / RetryRollbacking / TxTimeoutCheck）——
--        这 4 行是服务端定时任务抢锁用的，缺了会拿不到锁；
--     ③ global_table 的索引名由 idx_gmt_modified_status 改为 idx_status_gmt_modified（仅改名）；
--     ④ 全库字符集统一 utf8mb4。
--
-- 幂等性：本脚本按"先判断再执行"写成，可重复执行（已在本机 seata 库实测跑过一遍）。
-- 执行：mysql -uroot -p123 seata < 03b-seata-server-1.5.2-upgrade.sql
--      （脚本里已带 USE `seata`;，所以 `mysql -uroot -p < 03b-....sql` 也能跑。
--        下面的守卫用 DATABASE() 取当前库，**必须**能取到 seata，所以这句 USE 不能省）
-- （全新安装可直接用更新过的 03-seata-server.sql，它已含 1.5.2 的全部 4 张表）
-- ============================================================
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
