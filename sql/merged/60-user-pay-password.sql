-- =====================================================================
-- 60-user-pay-password.sql
-- 拆分「登录密码」与「支付密码」：llmall-user.user 新增 pay_password 列
--
-- ============================ 一、功能 ============================
-- 给 llmall-user.user 新增 pay_password 列，并把存量行里的明文占位默认值改写为 bcrypt 哈希，
-- 使"登录口令"与"支付口令"从同一列解耦成两列，堵死"知道手机号即可登录并支付"的账号接管路径。
--
-- ============================ 二、背景（真实安全缺陷） ============================
-- user.password 一列同时承担两个职责：
--   ① 用户名 + 密码登录的校验依据（UserServiceImpl#login）
--   ② 余额支付的支付密码校验依据（UserServiceImpl#deductMoney）
-- 而短信验证码登录带"未注册自动注册"：注册时用户名由手机号派生（u_{手机号}，公开可推导）、
-- 口令被写成固定值 123456。于是"知道手机号"就等于"能登录该账号"：
-- 攻击者可直接 POST /users/login {"username":"u_138xxxx1234","password":"123456"}
-- 绕过短信验证码接管账号，并可用同一口令发起余额支付。
--
-- ============================ 三、修复策略（两层，本脚本是库层） ============================
--   1) 库层（本脚本）：新增 pay_password 列，NOT NULL DEFAULT '123456'。
--      MySQL 执行 ADD COLUMN 时会把全部既有行填充为该默认值，
--      因此存量账号的余额支付口令仍是 123456，登录与支付行为完全不变；
--      同时把"登录口令"与"支付口令"解耦成两列。
--   2) 应用层：自动注册改为
--        password     = 随机 24 位（不可预期，不返回给任何调用方 → 堵死"知道手机号即可登录"）
--        pay_password = 123456 的 bcrypt 哈希（可预期，否则新账号无法完成余额支付）
--      且 deductMoney 只读 pay_password 列做校验。
--
-- ============================ 四、影响面 ============================
--   · 结构：llmall-user.user 新增一列，全部既有行都会被填充默认值。
--   · 数据：存量行的 pay_password 先被填为明文占位值 '123456'，
--           再由本脚本第二段统一替换为 bcrypt 哈希。
--   · 本脚本不改动任何账号的登录口令。存量账号若仍使用固定口令 123456，
--     账号接管路径依然存在，需人工重置（见"六、上线必做"第 3 条）。
--     之所以不在这里自动重置，是因为重置后用户将无法再用用户名密码登录，
--     属于产品决策而非纯技术修复，必须由使用方显式执行（下方给了现成语句）。
--   · 不受影响：password（登录口令）列、余额数据、其它任何业务表；
--     已自行改过支付密码的账号不受第二段影响（WHERE 只命中明文占位值）。
--
-- ============================ 五、幂等性与回滚 ============================
-- 幂等：可重复执行。先查 information_schema.COLUMNS 判断列是否已存在，存在则跳过
--   （与 21-user-alter.sql / 22-user-time-default.sql 同一套房规）；
--   第二段 UPDATE 的 WHERE 只命中"仍是明文占位值"的行，重复执行命中 0 行，
--   不会覆盖用户已经设置过的支付密码。
-- 回滚：应用回退到"不读 pay_password"的版本后执行
--     ALTER TABLE `llmall-user`.`user` DROP COLUMN `pay_password`;
--   【注意】DROP 会连同该列上已设置的支付密码一起丢失，回滚前必须备份该列；
--   第二段的 UPDATE 请先用备份表或导出留底后再执行。
--
-- ============================ 六、前置依赖与上线必做 ============================
-- 前置：llmall-user 库与 user 表已存在（00-create-databases.sql / 50-seed-local-life-data.sql）。
--   1) 先执行本脚本，再发布应用：应用启动即依赖 pay_password 列，顺序反了会报 Unknown column。
--   2) 落库校验默认值确已生效：
--        SELECT COUNT(*) AS bad FROM `llmall-user`.`user`
--         WHERE `pay_password` IS NULL OR `pay_password` = '';
--      应输出 0。
--   3) （可选，需产品决策）重置存量账号的登录口令，使其不可预期；
--      把 <bcrypt-hash> 换成本系统 PasswordEncoder 生成的哈希
--      （演示环境可用 123456 的哈希保持体验）：
--        UPDATE `llmall-user`.`user` SET `password` = '<bcrypt-hash>' WHERE `password` = '<旧哈希>';
--
-- ============================ 七、风险等级 ============================
-- 中 —— 纯新增列（可 DROP 回滚），但第二段会改写全部存量行的支付口令，
--       且执行顺序反了会导致应用启动失败（可发现、可恢复）。
--
-- 执行：mysql -uroot -p123 llmall-user < 60-user-pay-password.sql
-- =====================================================================

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'llmall-user' AND TABLE_NAME = 'user'
             AND COLUMN_NAME = 'pay_password');
SET @s := IF(@c = 0,
    'ALTER TABLE `llmall-user`.`user`
       ADD COLUMN `pay_password` VARCHAR(128) NOT NULL DEFAULT ''123456''
         COMMENT ''支付密码（加密存储），仅用于余额支付校验；与登录密码 password 分离'' AFTER `password`',
    'SELECT ''llmall-user.user.pay_password 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------------------------------------------------
-- 【重要】把 ADD COLUMN 的明文占位默认值替换为 bcrypt 哈希
--
-- 原因：ADD COLUMN 的 DEFAULT 会把既有行一次性填充为字面量 '123456'，
--       而应用侧 passwordEncoder.matches() 比对的是 BCrypt 哈希。
--       对从未登录过的账号，该行会一直停在明文状态：
--         · 明文与哈希无法匹配 → 这些账号的余额支付实际不可用（功能缺口）；
--         · 且明文口令可直接被人使用（比哈希更糟）。
--       两条路径都把明文默认值补成哈希后，问题彻底消失。
--
-- 下面的哈希由项目实际使用的 BCryptPasswordEncoder 生成并校验通过（明文 123456）：
--     $2a$10$n5qsrxCUqQP.Asc6LFPNruxdxOr1bARG4tEyX7iDWni6wQIu3R7HO
-- 幂等：WHERE 只命中"仍是明文占位值"的行，重复执行命中 0 行，不会覆盖用户已设置的支付密码。
-- ---------------------------------------------------------------------
UPDATE `llmall-user`.`user`
   SET `pay_password` = '$2a$10$n5qsrxCUqQP.Asc6LFPNruxdxOr1bARG4tEyX7iDWni6wQIu3R7HO'
 WHERE `pay_password` = '123456';

-- 校验：
--   bad = 0   → 无空值
--   plain = 0 → 无残留明文占位值
SELECT COUNT(*) AS bad FROM `llmall-user`.`user`
 WHERE `pay_password` IS NULL OR `pay_password` = '';
SELECT COUNT(*) AS plain_text_left FROM `llmall-user`.`user`
 WHERE `pay_password` = '123456';
