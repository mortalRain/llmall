-- ============================================================
-- 21 · 用户域改造（llmall-user-service / llmall-user）
-- 来源：商城 user + 点评 tb_user / tb_user_info / tb_sign
-- 合并原则：以商城 user 表为骨架（username + BCrypt），补点评字段；
--          点评的用户画像字段落到 user_info（1:1）；签到独立成 user_sign。
-- ⚠️ 执行前先备份 llmall-user 库。
--
-- 幂等性：本脚本可**重复执行**。两处 ALTER 用"先查 information_schema 再决定执行还是跳过"的
--   守卫写法（与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：
--     列查 COLUMNS → @c；索引查 STATISTICS → @c；命中则改跑一句 SELECT '…跳过'。
-- ============================================================

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
--       （这是**数据**问题不是幂等问题：唯一索引本身已存在时会跳过，不会重复报错）
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

-- 4) 积分流水（**归用户域**：credits 是用户资产，流水必须与资产同库同事务才能保证"加积分 + 记流水"原子完成）
--    (biz_type, biz_id, type) 唯一索引 = 积分发放的幂等闸门（亮点 B 用），同时是对账的判据。
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
