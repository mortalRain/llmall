-- ============================================================
-- 11 · 商品域改造（llmall-item-service / llmall-item）
-- 来源：商城 item 表
-- 改造点：① 商品挂到门店下（本地生活场景：门店卖套餐）
--        ② 新增 stock_flow 库存流水 —— 亮点 B 的对账依据（有流水才能对账）
--
-- 幂等性：本脚本可**重复执行**。ALTER 一律用"先查 information_schema 再决定执行还是跳过"的
--   守卫写法（与 03b-seata-server-1.5.2-upgrade.sql 完全同一套房规）：
--     · 查 COLUMNS    判断列是否已存在 → 存进 @c
--     · 查 STATISTICS 判断索引是否已存在 → 存进 @c
--     · SET @s := IF(@c = 0, '<原 ALTER>', 'SELECT ''…跳过''') 后 PREPARE / EXECUTE / DEALLOCATE
--   效果与首次执行完全一致（全新库上 @c=0，执行的仍是原来那条 ALTER）。
-- ============================================================

USE `llmall-item`;

-- 商品/套餐归属门店（原商城是纯电商，无门店概念）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item'
             AND COLUMN_NAME IN ('shop_id', 'type'));
SET @s := IF(@c = 0,
    'ALTER TABLE `item`
       ADD COLUMN `shop_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''所属门店 id（本地生活场景）'' AFTER `id`,
       ADD COLUMN `type`    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT ''1 实物商品 2 门店套餐/团购券'' AFTER `shop_id`',
    'SELECT ''item.shop_id / item.type 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item' AND INDEX_NAME = 'idx_shop');
SET @s := IF(@c = 0,
    'ALTER TABLE `item` ADD KEY `idx_shop` (`shop_id`)',
    'SELECT ''idx_shop 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 库存扣减流水：每一次库存变动都留痕，(biz_type, biz_id, item_id) 唯一 = 幂等 + 可对账
CREATE TABLE IF NOT EXISTS `stock_flow` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `item_id`     BIGINT UNSIGNED NOT NULL COMMENT '商品/SKU id',
  `shop_id`     BIGINT UNSIGNED          DEFAULT NULL COMMENT '冗余门店 id',
  `change_num`  INT             NOT NULL COMMENT '变动数量（负数=扣减，正数=回滚/入库）',
  `biz_type`    VARCHAR(64)     NOT NULL COMMENT '业务类型，如 ORDER_CREATED / ORDER_CANCELED / SECKILL',
  `biz_id`      VARCHAR(64)     NOT NULL COMMENT '业务主键（订单号）',
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1 有效 2 已回滚',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`, `item_id`) COMMENT '幂等：同一订单同一商品只扣一次',
  KEY `idx_item` (`item_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存变动流水表';

-- 说明：对账任务（亮点 B）会比对
--   llmall-order.order（已支付订单）
--   ↔ llmall-item.stock_flow（库存扣减流水）
--   ↔ llmall-promotion.points_flow（积分发放流水）
-- 任意一侧缺失即判定为"消息丢失"，生成补偿消息重投。

-- ------------------------------------------------------------
-- 商品列表的索引：首页/分类页按 (type, status) 过滤、按 sold 排序；门店页按 shop_id 过滤
-- 没有这两个索引时 8.8 万行商品的分页查询会走全表扫（实测：status 单列索引对 type+status 组合选择率不足）
--
-- 【修复记录 · 2026-10 审计】原文件此处写成 “ALTER TABLE item 的表名反引号后多一个字母 r”，
--   语法错误，这条语句**永远执行不了**，两个索引因此从未被建出来（线上库是后来手工补的）。
--   原意判定依据：① 紧邻的上一行注释点名的就是这两个索引；② 线上库
--   llmall-item.item 实际存在的索引形态正是 idx_type_status(type,status) 与 idx_shop_sold(shop_id,sold)。
--   现按原意修正，并套用上面的幂等守卫（已存在则跳过）。
-- ------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item'
             AND INDEX_NAME IN ('idx_type_status', 'idx_shop_sold'));
SET @s := IF(@c = 0,
    'ALTER TABLE `item`
       ADD KEY `idx_type_status` (`type`, `status`),
       ADD KEY `idx_shop_sold` (`shop_id`, `sold`)',
    'SELECT ''idx_type_status / idx_shop_sold 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
