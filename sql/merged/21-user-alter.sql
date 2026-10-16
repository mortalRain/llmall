-- =====================================================================
-- 21-user-alter.sql
-- 21 · 用户域改造（llmall-user-service / llmall-user）
--
-- 在商城 user 表（由上级目录 sql/llmall-user.sql 导入）之上，合并点评的
-- tb_user / tb_user_info / tb_sign。
--
-- 执行：mysql -uroot -p < 21-user-alter.sql
--
-- ============================ 一、功能 ============================
-- 给 llmall-user.user 补 nick_name / icon 两列，补 uk_phone 唯一索引；
-- 并新建 user_info（用户详细资料，1:1）、user_sign（签到）、points_flow（积分流水）三张表。
--
-- ============================ 二、背景 ============================
-- 合并原则：以商城 user 表为骨架（username + BCrypt），补点评体系需要的字段；
--   点评的用户画像字段落到 user_info（1:1，不把这些列堆进 user 表）；
--   签到独立成 user_sign（按天一条，靠唯一键防重复签到）。
-- 三处口径如果不这样定，会直接出问题：
--   ① phone 必须唯一：点评体系靠手机号验证码登录，且短信登录带"未注册自动注册"，
--      手机号重复时验证码会绑到多个账号上，账号归属无法判定；
--   ② 积分流水必须与积分资产同库同事务（credits 在 user_info）：放营销域会导致
--      "积分加了但流水没写"的跨库不一致，反而制造新的分布式事务；
--   ③ points_flow 的 uk_biz(biz_type, biz_id, type) 唯一 = 积分发放的幂等闸门
--      （消息重投、接口重试都靠它去重），同时是积分对账的判据。缺这个唯一键，
--      重复投递会让同一笔订单的积分发两次。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-user.user 新增 nick_name / icon 两列（ADD COLUMN 会把全部存量行
--     填充为 NULL），并新增 uk_phone 唯一索引。
--   · 受影响：llmall-user 库新增 user_info / user_sign / points_flow 三张表。
--   · 不受影响：user 表的既有列与行数据 —— 除上述两列的 NULL 填充外不改写任何已有值；
--     登录口令 password、余额 balance 完全不动（口令相关改造见 60 / 61 号脚本）。
--   · 不受影响：其它库。llmall-user.undo_log / consume_record 分别由 01 / 02 建，
--     llmall-user.balance_flow 由 04-balance-flow.sql 建，均不在本脚本内。
--   · 【注意】uk_phone 加不加得上取决于存量数据：若历史数据里 phone 有重复值，
--     ALTER 会直接失败（ERROR 1062）。这是数据问题，不是幂等问题 ——
--     唯一索引已存在时守卫会跳过，不会重复报错。
--
-- ============================ 四、执行步骤 ============================
--   1. 执行前先备份 llmall-user 库（不可跳过）：
--        mysqldump -uroot -p llmall-user > backup-llmall-user-before-21.sql
--   2. 清洗重复手机号（不可跳过，否则第 3 步的 uk_phone 一定失败）：
--        SELECT `phone`, COUNT(*) FROM `llmall-user`.`user`
--         GROUP BY `phone` HAVING COUNT(*) > 1;
--      有结果就先合并/置空重复行，再继续。
--   3. 执行：mysql -uroot -p < 21-user-alter.sql
--   4. 核对（期望：2 列 + 1 索引 + 3 表）：
--        SELECT COLUMN_NAME FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA='llmall-user' AND TABLE_NAME='user' AND COLUMN_NAME IN ('nick_name','icon');
--        SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS
--         WHERE TABLE_SCHEMA='llmall-user' AND TABLE_NAME='user' AND INDEX_NAME='uk_phone';
--        SELECT TABLE_NAME FROM information_schema.TABLES
--         WHERE TABLE_SCHEMA='llmall-user' AND TABLE_NAME IN ('user_info','user_sign','points_flow');
--   5. （默认不执行）脚本末尾"数据迁移"段：把点评用户搬过来。默认注释状态，
--      执行前必须先定密码策略（见该段说明）。
-- 幂等：本脚本可重复执行。两处 ALTER 用"先查 information_schema 再决定执行还是跳过"的
--   守卫写法（与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：
--     列查 COLUMNS → @c；索引查 STATISTICS → @c；命中则改跑一句 SELECT '…跳过'。
--
-- ============================ 五、前置依赖 ============================
--   1) 上级目录 sql/llmall-user.sql（建 llmall-user 库与 user 表）。
--   2) 00-create-databases.sql（本脚本依赖不到，但 01 / 02 建 llmall-user 的基础表需要）。
--   3) 下游依赖：04-balance-flow.sql 依赖 user 表已存在（balance_flow 与 user.balance 对账）；
--      60-user-pay-password.sql 依赖 user 表；50 / 51 的演示账号数据依赖 user_info / user_sign /
--      points_flow 三张表；22-user-time-default.sql 也改 user 表。
--   4) 顺序反了会怎样：
--      · user 表不存在 → 守卫查不到列（@c = 0）后仍去 ALTER，报 ERROR 1146，
--        且报错点与真实原因不符；
--      · 04 / 60 / 50 / 51 先于本脚本 → 分别报表不存在或列不存在；
--      · 本脚本与 22 号脚本改的是同一张表的不同列，二者可任意先后，但都必须早于应用发布
--        （应用启动即读 nick_name / user_info）。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不读新列/新表"的版本后执行：
--   DROP TABLE `llmall-user`.`points_flow`;
--   DROP TABLE `llmall-user`.`user_sign`;
--   DROP TABLE `llmall-user`.`user_info`;
--   ALTER TABLE `llmall-user`.`user` DROP INDEX `uk_phone`;
--   ALTER TABLE `llmall-user`.`user` DROP COLUMN `icon`, DROP COLUMN `nick_name`;
-- 【部分不可逆】
--   · 列与索引可逆：DROP 后重跑本脚本即恢复。
--   · points_flow 不可逆：它是积分发放唯一的流水依据，DROP 后"积分为什么是这个数"
--     再也无法解释，也无法重算（积分变更散落在下单、评价、签到多条链路）。
--     DROP 前必须导出：mysqldump -uroot -p llmall-user points_flow user_info > backup-21-user.sql
--   · 已写入的昵称/头像无法从备份外恢复：DROP COLUMN 会丢掉用户填过的所有昵称与头像 URL。
--   · 本脚本"不做"的事同样重要：它不会重置任何口令，也不会改余额，
--     因此回滚不需要动 password / balance，客服口径只需说明"昵称与积分展示消失"。
--
-- ============================ 附、风险等级 ============================
-- 中 —— 会改写存量表 user 的结构（新增两列 + 唯一索引），且新建的 points_flow / user_info
--       承载会被持续写入的用户资产数据；判据：结构改动本身可用备份还原，但索引可能因
--       重复手机号而失败、流水表被写入后无法重建，属"可恢复但需人工判断"，故为中；
--       脚本不触碰 password / balance，不涉及账号安全与资金划转，不到高。
-- =====================================================================

USE `llmall-user`;

-- 1) user 表补齐点评体系需要的字段
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
             AND COLUMN_NAME IN ('nick_name', 'icon'));
SET @s := IF(@c = 0,
    'ALTER TABLE `user`
       ADD COLUMN `nick_name` VARCHAR(32)  DEFAULT NULL COMMENT ''昵称（来自点评 tb_user）'' AFTER `username`,
       ADD COLUMN `icon`      VARCHAR(255) DEFAULT NULL COMMENT ''头像（来自点评 tb_user）'' AFTER `nick_name`',
    'SELECT ''user.nick_name / user.icon 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- phone 加唯一索引（点评体系靠手机号验证码登录）
-- 注意：若历史数据存在重复 phone 或 NULL 较多，先清洗再加索引
--       （这是数据问题不是幂等问题：唯一索引本身已存在时会跳过，不会重复报错）
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'uk_phone');
SET @s := IF(@c = 0,
    'ALTER TABLE `user` ADD UNIQUE KEY `uk_phone` (`phone`)',
    'SELECT ''uk_phone 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 用户详细资料（点评 tb_user_info 平移）
CREATE TABLE IF NOT EXISTS `user_info` (
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '用户 id（主键，1:1）',
  `city`        VARCHAR(64)              DEFAULT '' COMMENT '城市',
  `introduce`   VARCHAR(128)             DEFAULT NULL COMMENT '个人介绍（≤128 字）',
  `fans`        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '粉丝数',
  `followee`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '关注数',
  `gender`      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 未知 1 男 2 女',
  `birthday`    DATE                     DEFAULT NULL COMMENT '生日',
  `credits`     INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '积分（与 promotion.points_flow 对账）',
  `level`       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '会员等级 1~9，0 未开通',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户详细资料表';

-- 3) 签到（点评 tb_sign 平移 + 唯一索引防重复签到）
CREATE TABLE IF NOT EXISTS `user_sign` (  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `year`        YEAR            NOT NULL,
  `month`       TINYINT         NOT NULL,
  `date`        DATE            NOT NULL COMMENT '签到日期',
  `is_backup`   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否为补签 0 否 1 是',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_date` (`user_id`, `date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户签到表';

-- 4) 积分流水（归用户域：credits 是用户资产，流水必须与资产同库同事务才能保证"加积分 + 记流水"原子完成）
--    (biz_type, biz_id, type) 唯一索引 = 积分发放的幂等闸门，同时是对账的判据。
CREATE TABLE IF NOT EXISTS `points_flow` (
  `id`          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED  NOT NULL COMMENT '用户 id',
  `points`      INT              NOT NULL COMMENT '积分变动值（正数获得，负数消耗）',
  `type`        TINYINT UNSIGNED NOT NULL COMMENT '1 下单获得 2 评价获得 3 签到 4 消耗',
  `biz_type`    VARCHAR(64)      NOT NULL COMMENT '业务类型，如 ORDER_PAID',
  `biz_id`      VARCHAR(64)      NOT NULL COMMENT '业务主键（订单号）',
  `remark`      VARCHAR(255)              DEFAULT NULL,
  `create_time` TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`, `type`) COMMENT '幂等闸门：同一业务同一类型只允许一条',
  KEY `idx_user` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分流水表';

-- ------------------------------------------------------------
-- 数据迁移（把点评用户搬过来）
-- 密码策略：点评侧是 MD5，商城侧是 BCrypt —— 统一为 BCrypt，
--          历史测试数据建议直接重置密码，不要留两套校验分支（面试会被追问）。
-- ------------------------------------------------------------
-- INSERT INTO `llmall-user`.`user` (id, username, password, phone, nick_name, icon, create_time, update_time, status, balance)
-- SELECT id, CONCAT('u_', phone), password, phone, nick_name, icon, create_time, update_time, 1, 0
-- FROM `dianping_src`.`tb_user`;
--
-- INSERT INTO `llmall-user`.`user_info` (user_id, city, introduce, fans, followee, gender, birthday, credits, level)
-- SELECT user_id, city, introduce, fans, followee, gender, birthday, credits, level FROM `dianping_src`.`tb_user_info`;
