-- ============================================================
-- 31 · 交易域改造（order-service，原 llmall-trade-service / 库 llmall-order）
-- 来源：商城 order（实物订单）+ 点评 tb_voucher_order（团购券订单）
-- 合并原则：订单只有一张表、一个状态机，用 order_type 区分业务类型。
--
-- 幂等性：本脚本可**重复执行**。两处 ALTER 用"先查 information_schema 再决定执行还是跳过"的
--   守卫写法（与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：
--     列查 COLUMNS → @c；索引查 STATISTICS → @c；命中则改跑一句 SELECT '…跳过'。
-- ============================================================

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
