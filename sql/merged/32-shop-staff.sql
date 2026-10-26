-- =====================================================================
-- 32-shop-staff.sql
-- 32 · 门店员工表（llmall-shop.shop_staff）—— 核销权限的唯一事实来源
--
-- 执行：mysql -uroot -p < 32-shop-staff.sql
--
-- ============================ 一、功能 ============================
-- 1) 在 llmall-shop 库建出门店员工表 shop_staff：一行 = "某用户是某门店的员工"；
-- 2) 为演示账号 Jack 预置 shop_id = 10 与 shop_id = 1 的 STAFF 身份（仅演示库）。
--
-- ============================ 二、背景 ============================
-- 修复「核销越权」：修复前 POST /orders/verify 只校验"已登录 + 核销码存在 + 门店一致"，
-- 任何登录用户拿到核销码都能把别人的订单核销掉 —— 核销是门店侧动作，决定门店结算与
-- 用户"券已使用"的终态，属于必须由后端强制的权限，不能只靠前端隐藏按钮。
--
-- 修复后的判据是"调用者是不是该订单门店的员工"，因此必须先有"员工"这个概念落库：
--   · 为什么不用 user 表的角色字段：一个用户可能同时在多家门店任职（演示账号 Jack 就是
--     1 号与 10 号两家店的员工），单值角色字段表达不了这种多对多关系；
--   · 为什么放在 llmall-shop 库：员工关系是门店域的从属数据，和门店同库才能让
--     "门店是否还在营业""员工是否仍在本店"这类判断留在同一域内；
--   · user_id 是跨库逻辑外键（llmall-user.user.id），不加物理外键：
--     两个库分属不同服务，物理外键会让用户域删号被门店域锁死，也违背"一个服务一个库"。
--
-- 纠错记录：**不要**用 user 表的 username 判断员工身份 —— 用户名可改、可重名策略变化，
-- 只有 user_id 稳定；本脚本的种子也是先按 username 查 id 再插入，落库的是 id。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-shop 库新增一张 shop_staff（CREATE TABLE IF NOT EXISTS，已存在则跳过）。
--   · 受影响：仅演示库的 shop_staff 会有 2 行种子（INSERT IGNORE，重复执行不变）。
--   · 不受影响：任何既有表结构与数据；不含 ALTER / UPDATE / DELETE。
--   · 不受影响：其它库 —— 种子只"读"llmall-user.user 的 id，不写它。
--   · 未执行本脚本就发布应用：shop 服务查不到表，核销接口会因员工校验失败而**拒绝**
--     （fail-closed），不会退化成"谁都能核销"；代价是所有核销都不可用，故必须与应用同批上线。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行（llmall-shop 库由它建，否则报 ERROR 1049）。
--   2. 执行：mysql -uroot -p < 32-shop-staff.sql
--   3. 核对表与唯一键（期望 1 行 uk_shop_user）：
--        SHOW CREATE TABLE `llmall-shop`.`shop_staff`;
--   4. 核对种子（期望 2 行；user_id 取 llmall-user.user 里 Jack 的真实 id）：
--        SELECT * FROM `llmall-shop`.`shop_staff` ORDER BY id;
--   5. 核对跨库读权限：种子用 `INSERT ... SELECT` 读 llmall-user.user，
--      执行账号需对该库有 SELECT 权限（root 默认有；权限不足时报 ERROR 1142）。
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE（uk_shop_user 兜底），可重复执行。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 llmall-shop 库）。
--   2) 10-shop.sql / 55-seed-core-shops.sql（门店 1 与 10 必须存在 —— 本脚本不建门店，
--      员工行指向不存在的门店时核销会因"门店不存在"走另一条错误路径，排查时容易误判）。
--   3) llmall-user 库已导入且存在 username = 'Jack' 的账号（原商城 llmall-user.sql）；
--      找不到 Jack 时本脚本**不插入**任何行（宁缺勿错：把权限挂到错的人身上比没有更危险）。
--   4) 顺序反了会怎样：先跑本脚本再跑 10-shop.sql → 本脚本仍能建表（同库无表依赖），
--      但种子行指向的门店尚不存在；等 10/55 跑完后员工行即生效，无需重跑本脚本。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不做员工校验"的版本后执行：
--   DROP TABLE `llmall-shop`.`shop_staff`;
-- 【注意】DROP 会丢掉全部门店员工关系，回退到修复前版本才能接受；若只是想把某个人
--   移出门店，应该删行而不是删表：
--     DELETE FROM `llmall-shop`.`shop_staff` WHERE shop_id = 10 AND user_id = 1;
--   删行是即时生效的（应用每次核销都实时查询，不做缓存），不需要重启服务。
--
-- ============================ 附、风险等级 ============================
-- 低 —— 纯新增表 + 2 行演示种子（INSERT IGNORE），不改写任何存量数据，DROP 即可完整回滚。
--       风险不在脚本本身，而在"应用先上线、脚本没跑"：届时核销会被 fail-closed 拒绝。
-- =====================================================================

USE `llmall-shop`;

-- 门店员工表。
--   role：当前代码只判"是不是本店员工"（STAFF / MANAGER 同等放行），保留字段是为了将来
--         "只有店长能退款/核销大额单"这类细分权限；不在此处加 CHECK 约束，取值由应用保证。
--   create_time：员工关系的建立时间，审计"某次核销发生时该员工是否已入职"时用得上。
--   唯一键 uk_shop_user 同时是幂等闸门：同一人重复加入同一门店只会有一行。
CREATE TABLE IF NOT EXISTS `shop_staff` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id`     BIGINT UNSIGNED NOT NULL COMMENT '门店 id（llmall-shop.shop.id）',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '员工用户 id（llmall-user.user.id，跨库逻辑外键，无物理外键）',
  `role`        VARCHAR(16)     NOT NULL DEFAULT 'STAFF' COMMENT '角色：STAFF 员工 / MANAGER 店长（当前鉴权只判是否本店员工）',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '成为该门店员工的时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_user` (`shop_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '门店员工（核销权限的唯一事实来源）';

-- ---------------------------------------------------------------------
-- 演示种子：Jack 是 1 号与 10 号门店的 STAFF（⚠️ 仅演示 / 冒烟库）
--
-- user_id 先查后插：演示库里 Jack 通常是 1，但**不能写死 1** —— 一旦库是重建过的，
-- 写死就会把 10 号店的核销权限挂到别的账号上（比没有权限更危险）。
-- 用 INSERT ... SELECT 从 llmall-user.user 取真实 id；Jack 不存在时插入 0 行。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `shop_staff` (`shop_id`, `user_id`, `role`)
SELECT s.shop_id, u.id, 'STAFF'
FROM (SELECT 10 AS shop_id UNION ALL SELECT 1) s
JOIN `llmall-user`.`user` u ON u.username = 'Jack';

-- 核对（执行后人工看一眼，期望 2 行）
-- SELECT id, shop_id, user_id, role, create_time FROM `llmall-shop`.`shop_staff` ORDER BY id;
