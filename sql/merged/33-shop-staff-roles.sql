-- =====================================================================
-- 33-shop-staff-roles.sql
-- 33 · 门店员工角色模型（OWNER/MANAGER/STAFF）+ 真实非员工账号 Rose
--
-- 执行：mysql -uroot -p < 33-shop-staff-roles.sql
-- 位置：必须在 32-shop-staff.sql 之后执行（表已存在）。
--
-- ============================ 一、这份脚本做什么 ============================
-- 1) 把演示账号 Jack 在 1 / 10 号店的角色从旧口径 STAFF 提升为 OWNER：
--    只有 OWNER 才能在"员工管理"页增删员工、授予/撤销 OWNER；核销权三种角色都有，
--    所以这次提升只增加管理能力，不改变 Jack 的核销能力。
-- 2) 把已有账号 Hope 种为 10 号店的 MANAGER（可选但建议保留）：
--    用于验证"店长可管理普通员工、但不能授予/撤销 OWNER"。
--    Hope 不存在时本脚本不会凭空造账号（避免出现无法登录的假员工）。
-- 3) 种一个"真实非员工"账号 Rose，供 E2E 断言"非员工访问核销台 / 直接调核销接口必须 403"：
--    - 优先复用 llmall-user.user 里已有的 Rose（演示库通常已有，id=2）；
--    - 口令复用 Jack 的 BCrypt 哈希（同一库、同一算法，明文 123456），不写死哈希；
--    - user_info 缺行时补一条基础资料；
--    - **不**把 Rose 加进 shop_staff —— 她必须是非员工，否则 403 用例失去意义。
--
-- ============================ 二、为什么改 Rose 的口令 ============================
-- 演示库已有的 Rose 口令不是 123456（登录返回"用户名或密码错误"），无法用于 E2E。
-- 需求明确：没有合适口令时插入 Rose 并复用 Jack 的 bcrypt 哈希。Rose 已存在（username 唯一），
-- 因此这里做"存在则对齐口令、不存在则插入"，等价于"复用同一个真实账号"，而不是造重复账号。
-- 【不要改 Jack 的口令】本脚本只读 Jack 的 password 列，不 UPDATE Jack。
--
-- ============================ 三、影响面 ============================
--   · llmall-shop.shop_staff：Jack 两行 role STAFF→OWNER；新增/修正 Hope 一行 MANAGER。
--   · llmall-user.user：仅 Rose 的 password（对齐 Jack 的哈希）；Rose 不存在时插入一行。
--   · llmall-user.user_info：Rose 缺行时补基础行；已有行不动（不覆盖演示资料）。
--   · 不动任何订单 / 地址 / 券 / 余额；不动 Jack 的 password / balance。
--   · 幂等：UPDATE 条件幂等，INSERT IGNORE / ON DUPLICATE 兜底，可重复执行。
--
-- ============================ 四、执行与核对 ============================
--   mysql -uroot -p < 33-shop-staff-roles.sql
--   期望：shop_staff 3 行（Jack 1/10 OWNER，Hope 10 MANAGER）；
--         Rose 不在 shop_staff；Rose 的 password 与 Jack 完全相同。
--
-- ============================ 五、回滚 ============================
--   角色回滚（回到旧口径）：
--     UPDATE `llmall-shop`.`shop_staff` s JOIN `llmall-user`.`user` u ON u.id=s.user_id
--       SET s.role='STAFF' WHERE u.username IN ('Jack','Hope');
--     DELETE FROM `llmall-shop`.`shop_staff` WHERE user_id=(SELECT id FROM `llmall-user`.`user` WHERE username='Hope');
--   Rose：本脚本只改口令，回滚口令需要从备份恢复；不确定 Rose 原口令时不要回滚。
-- =====================================================================

USE `llmall-shop`;

-- 存量表的列注释同步为三角色口径（只改元数据，不改数据/类型）
ALTER TABLE `shop_staff`
  MODIFY COLUMN `role` VARCHAR(16) NOT NULL DEFAULT 'STAFF'
  COMMENT '角色：OWNER 店主 / MANAGER 店长 / STAFF 员工';

-- 1) Jack：1 / 10 号店 OWNER（核心演示账号；核销权不变，新增管理权）
UPDATE `shop_staff` s
JOIN `llmall-user`.`user` u ON u.id = s.user_id
SET s.role = 'OWNER'
WHERE u.username = 'Jack'
  AND s.shop_id IN (1, 10);

-- 2) Hope：10 号店 MANAGER（已有账号；不存在则不插入）
--    已是 OWNER 时不降级（脚本重复执行 / 人工调过角色都安全）
INSERT INTO `shop_staff` (`shop_id`, `user_id`, `role`)
SELECT 10, u.id, 'MANAGER'
FROM `llmall-user`.`user` u
WHERE u.username = 'Hope'
ON DUPLICATE KEY UPDATE
  `role` = IF(`role` = 'OWNER', 'OWNER', 'MANAGER');

-- 3) Rose：真实非员工账号。优先复用已有用户；口令复用 Jack 的 BCrypt 哈希。
--    MySQL 的 SELECT ... INTO 在 Jack 不存在时得到 NULL，后续语句自动变成 no-op。
SET @jackPw = (SELECT `password` FROM `llmall-user`.`user` WHERE `username` = 'Jack' LIMIT 1);

-- 3.1 Rose 已存在：把登录口令对齐到 Jack 的哈希（明文同为 123456）
UPDATE `llmall-user`.`user`
SET `password` = @jackPw
WHERE `username` = 'Rose'
  AND @jackPw IS NOT NULL;

-- 3.2 Rose 不存在：插入一行（INSERT IGNORE 由 username 唯一键兜底）
INSERT IGNORE INTO `llmall-user`.`user`
  (`username`, `nick_name`, `password`, `pay_password`, `status`, `balance`, `create_time`, `update_time`)
SELECT 'Rose', 'Rose', @jackPw, '123456', 1, 1000000, NOW(), NOW()
WHERE @jackPw IS NOT NULL;

-- 3.3 user_info 基础行（已存在则不覆盖演示资料）
INSERT IGNORE INTO `llmall-user`.`user_info`
  (`user_id`, `city`, `introduce`, `fans`, `followee`, `gender`, `credits`, `level`)
SELECT u.id, '杭州', '演示账号：真实非门店员工，用于验证核销越权拦截（403）', 0, 0, 2, 0, 0
FROM `llmall-user`.`user` u
WHERE u.username = 'Rose';

-- 3.4 安全兜底：如果历史上误把 Rose 加进了门店员工表，这里删除，保证她始终是非员工
DELETE s FROM `shop_staff` s
JOIN `llmall-user`.`user` u ON u.id = s.user_id
WHERE u.username = 'Rose';

-- 核对 1：员工与角色（期望 3 行：Jack 1/10 OWNER + Hope 10 MANAGER）
SELECT s.shop_id, s.user_id, u.username, s.role, s.create_time
FROM `shop_staff` s
JOIN `llmall-user`.`user` u ON u.id = s.user_id
ORDER BY s.shop_id, s.id;

-- 核对 2：Rose 必须不在 shop_staff（期望 0 行）
SELECT s.*
FROM `shop_staff` s
JOIN `llmall-user`.`user` u ON u.id = s.user_id
WHERE u.username = 'Rose';

-- 核对 3：Rose 的口令哈希与 Jack 完全一致（期望 same_password = 1），且 user_info 有行
SELECT r.id AS rose_user_id, r.username,
       (r.password = j.password) AS same_password,
       (SELECT COUNT(*) FROM `llmall-user`.`user_info` i WHERE i.user_id = r.id) AS user_info_rows
FROM `llmall-user`.`user` r
JOIN `llmall-user`.`user` j ON j.username = 'Jack'
WHERE r.username = 'Rose';
