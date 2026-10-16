-- =====================================================================
-- 31-order-alter.sql
-- 31 · 交易域改造（llmall-trade-service（订单域）/ 库 llmall-trade）
--
-- 在商城 order 表（由上级目录 sql/llmall-trade.sql 导入）之上，合并点评的团购券订单。
--
-- 执行：mysql -uroot -p < 31-order-alter.sql
--
-- ============================ 一、功能 ============================
-- 给 llmall-trade.order 补 5 列（order_type / shop_id / voucher_id / verify_code / verify_time）
-- 与 3 个索引（idx_shop_time / idx_user_type / idx_verify_code），
-- 使"实物订单"与"门店套餐/团购券订单"共用一张表、一个状态机。
--
-- ============================ 二、背景 ============================
-- 合并原则：订单只有一张表、一个状态机，用 order_type 区分业务类型 ——
--   两套订单表会让"我的订单"要合并两个来源的分页，取消/退款/评价都要写两遍。
-- 每列都有明确用途，缺一列就少一条链路：
--   · order_type：1 实物订单 / 2 门店套餐/团购券订单，决定后续走发货还是核销；
--   · shop_id：门店订单必填，是"按门店查订单"与门店经营数据的基础；
--   · voucher_id：券类订单关联的券；
--   · verify_code / verify_time：到店核销的凭据与核销时间（status=7 时写入）。
-- 索引的口径（缺索引即全表扫）：
--   · idx_shop_time：按门店 + 时间查订单（门店后台/经营看板）；
--   · idx_user_type：按用户 + 订单类型查（"我的订单"按类型 Tab 分页）；
--   · idx_verify_code：核销码查重（generateVerifyCode）与核销校验（verifyOrder）都按
--     verify_code 查，8.8 万级订单量下没索引就是全表扫。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-trade.order 新增 5 列（ADD COLUMN 会把存量订单一次性填充：
--     order_type = 1、shop_id / voucher_id / verify_code / verify_time = NULL），
--     并新增 3 个索引。
--   · 不受影响：order 表的既有列与行数据 —— 除上述新列的默认值填充外不改写任何已有值，
--     订单状态、金额、支付信息全部不动。
--   · 不受影响：llmall-trade.order_detail 与其它任何表；其它库。
--     llmall-trade 的 undo_log / message_log / consume_record 分别由 01 / 02 建，不在本脚本内。
--   · 【注意】存量订单的 order_type 会被填成 1（实物订单），这正是它们的真实语义；
--     把存量行批量改成 2 会让它们被当成门店券订单、要求核销码，状态机随之走错分支。
--   · 【注意】order_type 只加列不加 CHECK 约束：取值 1/2 由应用保证，
--     脚本层无法拦住写入非法值（例如 3）。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认上级目录原商城脚本已导入（llmall-trade 库与 order 表存在）。不可跳过：
--      库不存在时 USE 报 ERROR 1049，且守卫查不到列（@c = 0）后仍去 ALTER，
--      最终报 ERROR 1146 Table 'order' doesn't exist，报错点与真实原因不符。
--   2. 执行：mysql -uroot -p < 31-order-alter.sql
--   3. 核对（期望：5 列 + 3 索引）：
--        SELECT COLUMN_NAME FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA='llmall-trade' AND TABLE_NAME='order'
--           AND COLUMN_NAME IN ('order_type','shop_id','voucher_id','verify_code','verify_time');
--        SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS
--         WHERE TABLE_SCHEMA='llmall-trade' AND TABLE_NAME='order'
--           AND INDEX_NAME IN ('idx_shop_time','idx_user_type','idx_verify_code');
--   4. 核对存量订单的 order_type 已填为 1（期望 0 行）：
--        SELECT COUNT(*) FROM `llmall-trade`.`order` WHERE `order_type` IS NULL;
-- 幂等：本脚本可重复执行。两处 ALTER 用"先查 information_schema 再决定执行还是跳过"的
--   守卫写法（与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：
--     列查 COLUMNS → @c；索引查 STATISTICS → @c；命中则改跑一句 SELECT '…跳过'。
--
-- ============================ 五、前置依赖 ============================
--   1) 上级目录 sql/llmall-trade.sql（建 llmall-trade 库与 order / order_detail 表）。
--   2) 00-create-databases.sql（本脚本依赖不到，但 01 / 02 建 llmall-trade 的基础表需要）。
--   3) 库名口径：订单库就是 llmall-trade，不存在 llmall-order ——
--      llmall-trade-service 的 application.yaml 里 llmall.db.database = llmall-trade，
--      本目录其它订单相关脚本（02 / 50 / 51 / 52 / 99）写的也都是 llmall-trade。
--   4) 下游依赖：50-seed-local-life-data.sql（按 order_type / shop_id / verify_code 插演示订单）、
--      51-seed-more-data.sql（写 order_logistics）、52-fix-orphan-pay-orders.sql
--      （按 order.id 判孤儿支付单）。
--   5) 顺序反了会怎样：order 表不存在 → ERROR 1146；
--      50 / 51 / 52 先于本脚本 → 分别报 Unknown column 'order_type' / 'shop_id' / 读不到订单，
--      且 50 是"先删后插"，会在半途留下不完整的演示订单。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不读新列"的版本后执行：
--   ALTER TABLE `llmall-trade`.`order` DROP KEY `idx_verify_code`;
--   ALTER TABLE `llmall-trade`.`order` DROP KEY `idx_user_type`, DROP KEY `idx_shop_time`;
--   ALTER TABLE `llmall-trade`.`order`
--     DROP COLUMN `verify_time`, DROP COLUMN `verify_code`,
--     DROP COLUMN `voucher_id`,  DROP COLUMN `shop_id`, DROP COLUMN `order_type`;
-- 【不可逆】这五列承载的是订单的业务属性，不是可以重算的派生值：
--   · 门店券订单的 shop_id / voucher_id / verify_code / verify_time 一旦 DROP，
--     券与订单的关联、核销码与核销时间全部丢失 —— 用户到店无法核销，退款也无法判定
--     "是否已使用"，只能从备份恢复。
--   · DROP 前必须导出：
--       mysqldump -uroot -p llmall-trade order > backup-31-order.sql
--   · 索引与 order_type 的 DROP 属可逆（重跑本脚本即恢复），
--     但 order_type 一旦丢失，两类订单就再也分不开（存量行全被当实物订单）。
--   · 回滚顺序必须与建表顺序相反：先 DROP 索引再 DROP 列（列被索引引用时先删列会失败）。
--
-- ============================ 附、风险等级 ============================
-- 中 —— 会改写存量表 order 的结构（5 列 + 3 索引）并给存量订单填充 order_type，
--       这几列在写入后被删除即不可重建；判据：结构改动可用备份还原、可重跑本脚本恢复，
--       但一旦有券类订单写入，丢失的关联关系无法自动恢复，需人工介入，故为中；
--       脚本本身不改金额与状态、不涉及资金划转，不到高。
-- =====================================================================

-- 说明：沿用已有库名 llmall-trade（避免迁移；若将来改名为 llmall-order，把这里换掉即可）
USE `llmall-trade`;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order'
             AND COLUMN_NAME IN ('order_type', 'shop_id', 'voucher_id', 'verify_code', 'verify_time'));
SET @s := IF(@c = 0,
    'ALTER TABLE `order`
       ADD COLUMN `order_type`  TINYINT       NOT NULL DEFAULT 1 COMMENT ''1 实物订单 2 门店套餐/团购券订单'' AFTER `id`,
       ADD COLUMN `shop_id`     BIGINT UNSIGNED        DEFAULT NULL COMMENT ''门店 id（团购券订单必填）'' AFTER `order_type`,
       ADD COLUMN `voucher_id`  BIGINT UNSIGNED        DEFAULT NULL COMMENT ''券 id（团购券订单）'' AFTER `shop_id`,
       ADD COLUMN `verify_code` VARCHAR(32)            DEFAULT NULL COMMENT ''核销码（到店出示）'' AFTER `status`,
       ADD COLUMN `verify_time` TIMESTAMP     NULL     DEFAULT NULL COMMENT ''核销时间（status=7 时写入）'' AFTER `verify_code`',
    'SELECT ''order.order_type / shop_id / voucher_id / verify_code / verify_time 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 三个索引：idx_shop_time 按门店+时间；idx_user_type 按用户+订单类型；
--   idx_verify_code —— 核销码查重(generateVerifyCode)与核销校验(verifyOrder)都按 verify_code 查，
--   没索引就是全表扫。
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order'
             AND INDEX_NAME IN ('idx_shop_time', 'idx_user_type', 'idx_verify_code'));
SET @s := IF(@c = 0,
    'ALTER TABLE `order`
       ADD KEY `idx_shop_time` (`shop_id`, `create_time`),
       ADD KEY `idx_user_type` (`user_id`, `order_type`),
       ADD KEY `idx_verify_code` (`verify_code`)',
    'SELECT ''idx_shop_time / idx_user_type / idx_verify_code 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 订单状态机（扩展点评的核销态，统一到一张表）
--   1 未付款  2 已付款/待发货  3 已发货/待确认  4 交易成功
--   5 交易取消/订单关闭        6 交易结束/已评价
--   7 已核销（门店套餐/团购券到店使用后）
-- ------------------------------------------------------------
