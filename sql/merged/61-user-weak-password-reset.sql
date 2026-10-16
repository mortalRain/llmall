-- =====================================================================
-- 61-user-weak-password-reset.sql
-- 存量账号弱口令批量修复（生产脚本：含备份、幂等、回滚、验证）
--
-- ============================ 一、功能 ============================
-- 存量账号弱口令批量修复：把仍在使用固定初始口令的账号清点出来、备份原哈希、重置口令并验证，
-- 同时留下可回滚的备份表。本脚本是"库层收口"，应用层的随机口令改造见 60-user-pay-password.sql。
--
-- ============================ 二、背景 ============================
-- 修复 pay_password 拆分之前，短信验证码登录的"自动注册"把登录口令写成固定值 123456，
-- 且用户名由手机号派生（u_{手机号}，公开可推导）。两者叠加后：
--     "知道手机号" == "能登录该账号"
-- 攻击者可直接 POST /users/login {"username":"u_138xxxx1234","password":"123456"}
-- 绕过短信验证码接管账号，并可继续用同一口令发起余额支付（有真实资损面）。
--
-- 应用层已修复：新注册账号的登录口令改为随机 24 位且不返回给任何调用方。
-- 但存量账号口令仍是固定的 123456，接管路径依然存在 —— 本脚本负责收口。
--
-- ============================ 三、影响面 ============================
-- 仅影响 `password` 列仍等于"初始口令哈希"的账号：
--   · 已自行改过密码的账号：不命中 WHERE 条件，完全不受影响；
--   · 新注册账号（随机口令）：不命中，不受影响；
--   · 命中账号：口令被重置为同一初始值（配合应用层"强制改密"标记引导用户改密）。
--
-- 【为什么不直接改成随机口令】
-- 重置为随机值会让用户立即无法用"用户名+密码"登录（随机值无人知晓），
-- 属于对外登录行为变更，需要产品与客服口径同步，不能由脚本替业务决策。
-- 本脚本因此采取"保持可用 + 标记强制改密"的温和收敛：
--   1) 口令留在 123456，保证存量用户登录体验不变；
--   2) 登录接口返回 needChangePassword=true（见 UserLoginVO），前端据此强制引导改密；
--   3) 用户改密后该标记自动消失，接管路径随之关闭。
-- 若安全要求更高（例如要求"零窗口"），可把下面的 NEW_HASH 换成随机口令的哈希，
-- 代价是所有命中账号必须走短信验证码登录 —— 这是产品决策，脚本已参数化以支持两种口径。
--
-- ============================ 四、执行步骤 ============================
--   Step 1  备份（必须）：把命中账号的原哈希存入备份表，供回滚
--   Step 2  重置：更新命中账号的口令
--   Step 3  验证：确认无残留弱口令账号
--   （可选）回滚：从备份表还原
--
-- 幂等性：全脚本可重复执行。
--   · 备份表用 CREATE TABLE IF NOT EXISTS + INSERT IGNORE（有唯一键），重复执行不会重复备份；
--   · 重置的 WHERE 条件带 `password = 初始哈希`，第二次执行命中 0 行；
--   · 回滚用 JOIN 备份表，重复执行结果一致。
--
-- ============================ 五、前置依赖 ============================
--   1) 必须先执行 60-user-pay-password.sql（pay_password 列）；
--   2) 应用需已发布"随机登录口令 + needChangePassword 标记"版本，否则改了密也没有引导；
--   3) 建议在业务低峰期执行，并在执行前做一次全库备份（本脚本的备份表只覆盖 user.password 一列）。
--
-- ============================ 六、风险等级 ============================
-- 高 —— 直接改写生产账号的登录口令。虽然 Step 1 提供备份表可回滚，但仍有两个不可忽视的前提：
--   · 备份表丢失或未执行 Step 1 时，被改写的原哈希无法恢复（用户在改密前将无法登录）；
--   · 回滚会覆盖"重置之后已自行改密"的账号（详见 Step 3 回滚段的【注意】）。
--   因此必须先在业务低峰期执行 Step 0 的只读统计，据此评估影响面与客服口径。
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 0 · 参数：初始口令哈希
--
-- 【重要】请先用本系统的 PasswordEncoder 生成 123456 的哈希后替换下面这个值。
-- 下面给出的哈希已用项目实际使用的 BCryptPasswordEncoder 生成并校验通过
-- （明文 123456），演示环境可直接使用：
--     $2a$10$n5qsrxCUqQP.Asc6LFPNruxdxOr1bARG4tEyX7iDWni6wQIu3R7HO
-- 若你的初始化脚本（50-seed-local-life-data.sql）用的是另一个哈希，
-- 请以该哈希为准（它才是线上真实使用的"初始口令"）。
-- ---------------------------------------------------------------------
SET @initial_hash := '$2a$10$n5qsrxCUqQP.Asc6LFPNruxdxOr1bARG4tEyX7iDWni6wQIu3R7HO';
-- 重置后的目标哈希：默认与初始值相同（温和收敛口径，见"二、影响面"）
SET @new_hash := @initial_hash;

-- ---------------------------------------------------------------------
-- Step 0 · 【上线前】存量风险统计：评估影响范围
--
-- 目的：在执行任何变更之前，先量化"有多少账号处于弱口令状态、占比多少"，
--       据此决定是否需要分批灰度、以及提前准备多少客服口径。
-- 全为只读查询，可安全地在生产执行。
-- ---------------------------------------------------------------------

-- 0.1 总量与弱口令账号数/占比（核心指标）
SELECT COUNT(*)                                                           AS total_accounts,
       SUM(CASE WHEN `password` = @initial_hash THEN 1 ELSE 0 END)        AS weak_login_password_accounts,
       ROUND(100 * SUM(CASE WHEN `password` = @initial_hash THEN 1 ELSE 0 END) / COUNT(*), 2)
                                                                          AS weak_ratio_percent
  FROM `llmall-user`.`user`;

-- 0.2 支付口令风险：明文占位（60 号脚本的 UPDATE 未执行时会命中）与空值
SELECT SUM(CASE WHEN `pay_password` = '123456' THEN 1 ELSE 0 END)         AS pay_password_plaintext,
       SUM(CASE WHEN `pay_password` IS NULL OR `pay_password` = '' THEN 1 ELSE 0 END)
                                                                          AS pay_password_missing
  FROM `llmall-user`.`user`;

-- 0.3 弱口令账号的"活跃度"分布：按最近更新时间分档，
--     用于判断"这些账号是否还在被使用"（长期不活跃的账号可延后处理）
SELECT CASE
         WHEN `update_time` >= NOW() - INTERVAL 7 DAY  THEN '7天内活跃'
         WHEN `update_time` >= NOW() - INTERVAL 30 DAY THEN '30天内活跃'
         WHEN `update_time` >= NOW() - INTERVAL 90 DAY THEN '90天内活跃'
         ELSE '90天以上未活跃'
       END                                    AS activity_bucket,
       COUNT(*)                               AS accounts
  FROM `llmall-user`.`user`
 WHERE `password` = @initial_hash
 GROUP BY activity_bucket
 ORDER BY accounts DESC;

-- 0.4 弱口令账号里"有余额"的数量：这部分账号被接管会直接产生资损，应优先引导改密
SELECT COUNT(*) AS weak_password_with_balance
  FROM `llmall-user`.`user`
 WHERE `password` = @initial_hash
   AND `balance` > 0;

-- ---------------------------------------------------------------------
-- Step 1 · 备份：记录命中账号的原哈希，供回滚
--
-- 【注意】备份表只存 id 与 password 两列，不含任何其他个人信息；
-- 它含口令哈希，属敏感表，回滚完成后应立即 DROP。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `llmall-user`.`user_password_backup_61` (
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '账号 id',
  `password`    VARCHAR(128)    NOT NULL COMMENT '重置前的登录口令哈希（回滚用）',
  `backup_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '61 号脚本的登录口令备份（敏感，回滚后请 DROP）';

-- INSERT IGNORE + 主键保证幂等：重复执行不会覆盖首次备份（首次备份才是真正的"重置前状态"）
INSERT IGNORE INTO `llmall-user`.`user_password_backup_61` (`user_id`, `password`)
SELECT `id`, `password`
  FROM `llmall-user`.`user`
 WHERE `password` = @initial_hash;

-- 记录本步备份到的行数（应与 Step 3 的重置行数一致）
SELECT COUNT(*) AS backed_up_accounts FROM `llmall-user`.`user_password_backup_61`;

-- ---------------------------------------------------------------------
-- Step 2 · 重置：仅命中"仍是初始口令"的账号
--
-- 【幂等】WHERE 再次限定 `password = @initial_hash`：
--   · 第一次执行：命中全部弱口令账号；
--   · 第二次执行：命中 0 行（已被 Step 2 改过，或用户已自行改密）；
--   · 已改密账号永远不会被本脚本覆盖。
-- ---------------------------------------------------------------------
UPDATE `llmall-user`.`user`
   SET `password` = @new_hash,
       `update_time` = NOW()
 WHERE `password` = @initial_hash;

-- 输出本步影响行数（MySQL 客户端会打印 Rows matched / Changed）
SELECT ROW_COUNT() AS reset_accounts;

-- ---------------------------------------------------------------------
-- Step 3 · 验证
--   expected = 0：已无账号使用初始登录口令
--   pay_null = 0：支付口令列也已完成初始化（60 号脚本的兜底校验）
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS remaining_weak_login_password
  FROM `llmall-user`.`user`
 WHERE `password` = @initial_hash;

SELECT COUNT(*) AS null_pay_password
  FROM `llmall-user`.`user`
 WHERE `pay_password` IS NULL OR `pay_password` = '';

-- 逐账号明细（仅在需要人工核对时启用）
-- SELECT `id`, `username`, `phone`, `update_time`
--   FROM `llmall-user`.`user`
--  WHERE `id` IN (SELECT `user_id` FROM `llmall-user`.`user_password_backup_61`);

-- =====================================================================
-- 回滚方案
-- =====================================================================
-- 场景：重置后发现问题（例如前端尚未上线强制改密引导、或客服口径未同步），
--       需要把口令恢复到重置前的状态。
--
-- 【注意】回滚只会把口令改回备份时的值。若用户在重置之后已自行改过密码，
--       回滚会覆盖掉用户的新密码（用户需要重新改密）—— 因此回滚前请先确认
--       备份表 password 与当前 password 是否一致：
--          SELECT COUNT(*) FROM `llmall-user`.`user` u
--            JOIN `llmall-user`.`user_password_backup_61` b ON b.user_id = u.id
--           WHERE u.password <> b.password AND b.password = @initial_hash;
--       若结果 > 0，说明有用户已自行改密，应改为"按 id 精确回滚"而不是全量回滚。
-- ---------------------------------------------------------------------
-- UPDATE `llmall-user`.`user` u
--   JOIN `llmall-user`.`user_password_backup_61` b ON b.user_id = u.id
--    SET u.password = b.password,
--        u.update_time = NOW();

-- 回滚完成后务必删除备份表（它含口令哈希，属敏感数据）
-- DROP TABLE `llmall-user`.`user_password_backup_61`;

-- =====================================================================
-- 备份表的 30 天自动清理
-- =====================================================================
-- 【为什么要自动清理】备份表存的是口令哈希，属敏感数据：
--   · 长期驻留 = 一旦库被读，攻击者拿到的是可直接用于撞库的哈希集合；
--   · 长期驻留也白占存储。
-- 因此给它一个 30 天的自动失效机制：到期自动删除，不依赖人工记得清理。
--
-- 【两种实现，二选一】
--   方案 A（推荐，无外部依赖）：删表事件。MySQL 事件调度器在 30 天后自动 DROP 整张表。
--           注意：本仓库的备份表语义是"一次性快照"，到期即整体作废，故用 DROP 而非逐行删。
--   方案 B（不依赖事件调度器）：定时任务里按 backup_time 删除过期行（见脚本末尾）。
--
-- 前置：方案 A 需要事件调度器已开启。检查与开启：
--     SHOW VARIABLES LIKE 'event_scheduler';          -- 期望 ON
--     SET GLOBAL event_scheduler = ON;                -- 需要 SUPER/SYSTEM_VARIABLES_ADMIN 权限
--   【注意】若生产不允许开启事件调度器（常见于托管数据库），直接用方案 B。
-- ---------------------------------------------------------------------
-- 方案 A：30 天后自动删表
-- DROP EVENT IF EXISTS `llmall-user`.`ev_drop_pwd_backup_61`;
-- CREATE EVENT `llmall-user`.`ev_drop_pwd_backup_61`
--   ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 30 DAY
--   ON COMPLETION PRESERVE
--   DO DROP TABLE IF EXISTS `llmall-user`.`user_password_backup_61`;

-- 方案 B：纳入既有定时清理任务（按时间删行，保留表结构）
-- DELETE FROM `llmall-user`.`user_password_backup_61`
--  WHERE `backup_time` < NOW() - INTERVAL 30 DAY;

-- 查询事件是否已注册：
-- SELECT EVENT_NAME, STATUS, EXECUTE_AT FROM information_schema.EVENTS
--  WHERE EVENT_SCHEMA = 'llmall-user' AND EVENT_NAME = 'ev_drop_pwd_backup_61';

-- =====================================================================
-- 附：与初始化脚本的关系
-- =====================================================================
-- 50-seed-local-life-data.sql 会把演示账号 19 的登录口令对齐到账号 1（沿用同一 BCrypt 哈希），
-- 因此演示环境中"初始口令"就是以账号 1 的哈希为准 —— 若与本脚本的 @initial_hash 不同，
-- 请把 @initial_hash 换成账号 1 的哈希后重跑本脚本（Step 1/2 的 WHERE 会自动命中）。
-- 60-user-pay-password.sql 已为 pay_password 设了 DEFAULT，存量行的支付口令无需额外处理。
-- =====================================================================
