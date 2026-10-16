-- =====================================================================
-- 11-item-alter.sql
-- 11 · 商品域改造（llmall-item-service / llmall-item）
--
-- 在商城 item 表（由上级目录 sql/llmall-item.sql 导入）之上做增量改造。
--
-- 执行：mysql -uroot -p < 11-item-alter.sql
--
-- ============================ 一、功能 ============================
-- 给 item 表补 shop_id / type 两列与三个索引（idx_shop / idx_type_status / idx_shop_sold），
-- 并新建 stock_flow 库存变动流水表（库存扣减的对账依据）。
--
-- ============================ 二、背景 ============================
--   ① 原商城是纯电商：商品只有分类、没有门店归属，"门店详情页的套餐列表"无法实现 ——
--      补 shop_id（归属门店）+ type（1 实物商品 / 2 门店套餐/团购券）后，
--      同一张 item 表既能放京东实物商品，也能放门店套餐。
--   ② 库存只有一个当前值：扣错了、回滚了都没有痕迹，事后无法回答"库存为什么少了"。
--      stock_flow 让每一次库存变动都留痕，(biz_type, biz_id, item_id) 唯一 = 幂等 + 可对账。
--      没有流水就没有对账依据 —— 这是"库存对不上"这类问题的唯一修复入口。
--   ③ 列表查询的索引：首页/分类页按 (type, status) 过滤、按 sold 排序，门店页按 shop_id 过滤；
--      缺索引时 8.8 万行商品的分页查询会走全表扫（选择率依据见下方索引段的原地注释）。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-item.item 新增 shop_id / type 两列（ADD COLUMN 会把存量 8.8 万行
--     一次性填充默认值：shop_id = NULL、type = 1），并新增 3 个索引。
--   · 受影响：llmall-item 库新增 stock_flow 表。
--   · 不受影响：item 表的既有列与行数据（除上述两列的默认值填充外，不改写任何已有值）。
--   · 不受影响：其它库。llmall-item.undo_log 由 01-undo-log-all.sql 建，不在本脚本内。
--   · 【注意】shop_id 默认 NULL 表示"纯电商实物商品"，是按门店过滤时的天然排除条件；
--     把存量行的 shop_id 批量改成某个门店 id，会让 8.8 万条京东商品挂到该门店下。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认上级目录原商城脚本已导入（llmall-item 库与 item 表存在）。不可跳过：
--      库不存在时 USE 报 ERROR 1049，且守卫查询会查不到列（@c = 0）而继续去 ALTER，
--      最终报 ERROR 1146 Table 'item' doesn't exist，报错点与真实原因不符。
--   2. 执行：mysql -uroot -p < 11-item-alter.sql
--   3. 核对（期望：2 列 + 3 索引 + 1 表）：
--        SELECT COLUMN_NAME FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA='llmall-item' AND TABLE_NAME='item' AND COLUMN_NAME IN ('shop_id','type');
--        SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS
--         WHERE TABLE_SCHEMA='llmall-item' AND TABLE_NAME='item'
--           AND INDEX_NAME IN ('idx_shop','idx_type_status','idx_shop_sold');
--        SELECT COUNT(*) FROM information_schema.TABLES
--         WHERE TABLE_SCHEMA='llmall-item' AND TABLE_NAME='stock_flow';
-- 幂等：本脚本可重复执行。ALTER 一律用"先查 information_schema 再决定执行还是跳过"的
--   守卫写法（与 03b-seata-server-1.5.2-upgrade.sql 完全同一套房规）：
--     · 查 COLUMNS    判断列是否已存在 → 存进 @c
--     · 查 STATISTICS 判断索引是否已存在 → 存进 @c
--     · SET @s := IF(@c = 0, '<原 ALTER>', 'SELECT ''…跳过''') 后 PREPARE / EXECUTE / DEALLOCATE
--   效果与首次执行完全一致（全新库上 @c=0，执行的仍是原来那条 ALTER）。
--
-- ============================ 五、前置依赖 ============================
--   1) 上级目录 sql/llmall-item.sql（建 llmall-item 库与 item 表，约 35MB，需要耐心等）。
--   2) 00-create-databases.sql（本脚本自身不依赖它，但 01/02 建 llmall-item.undo_log 需要）；
--      10-shop.sql（item.shop_id 的业务含义指向 llmall-shop.shop.id ——
--      本脚本不建外键，所以顺序反了不会报错，只会让"按门店查商品"查不到数据）。
--   3) 顺序反了会怎样：item 表不存在 → ERROR 1146；10 未跑 → 结构正常但门店关联查询为空。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不读 shop_id/type/stock_flow"的版本后执行：
--   ALTER TABLE `llmall-item`.`item` DROP KEY `idx_shop`;
--   ALTER TABLE `llmall-item`.`item` DROP KEY `idx_type_status`, DROP KEY `idx_shop_sold`;
--   ALTER TABLE `llmall-item`.`item` DROP COLUMN `type`, DROP COLUMN `shop_id`;
--   DROP TABLE `llmall-item`.`stock_flow`;
-- 【不可逆】stock_flow 是库存唯一的变动账本，DROP 后无法重建（除非把全部历史订单重放一遍）：
--   DROP 前必须导出：mysqldump -uroot -p llmall-item stock_flow > backup-11-stock-flow.sql
--   列与索引的 DROP 属可逆（重跑本脚本即恢复），但注意 DROP COLUMN 会连带丢掉这两列的值：
--   shop_id 是门店套餐的归属、type 决定商品走实物订单还是核销订单，回收前先导出这两列：
--     SELECT id, shop_id, type FROM `llmall-item`.`item` INTO OUTFILE 'item-shop-type.csv';
--
-- ============================ 附、风险等级 ============================
-- 中 —— 会改写存量表结构并给 8.8 万行存量数据填充新列（type 默认 1、shop_id = NULL），
--       同时新建承载对账依据的 stock_flow；判据：改错后可以靠备份导出还原（操作前未备份
--       则 type/shop_id 的取值无法自动还原），需要人工介入，故为中；不涉及资金，不到高。
-- =====================================================================

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
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '1 有效（回补以新增正数流水表达，不存在 2 已回滚）',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`, `item_id`) COMMENT '幂等：同一订单同一商品只扣一次',
  KEY `idx_item` (`item_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存变动流水表';

-- 说明：对账任务会比对
--   llmall-trade.order（已支付订单）
--   ↔ llmall-item.stock_flow（库存扣减流水）
--   ↔ llmall-user.points_flow（积分发放流水）
-- 任意一侧缺失即判定为"消息丢失"，生成补偿消息重投。
-- 【注意】三处库名必须写对：订单库是 llmall-trade（不存在 llmall-order），
--   积分流水归用户域 llmall-user.points_flow（不是 llmall-promotion，见 20-promotion.sql 末尾说明）。
--   写错库名会把对账范围直接缩小，比对结果必然"全部匹配"，缺陷被静默掩盖。

-- ------------------------------------------------------------
-- 商品列表的索引：首页/分类页按 (type, status) 过滤、按 sold 排序；门店页按 shop_id 过滤
-- 没有这两个索引时 8.8 万行商品的分页查询会走全表扫（实测：status 单列索引对 type+status 组合选择率不足）
--
-- 【当前的不变量】这两条索引必须存在且形态是 idx_type_status(type,status) 与
--   idx_shop_sold(shop_id,sold)；语句若写坏（例如表名多一个字母），索引就永远建不出来，
--   而报错只在执行那一次出现，事后没人会再核对 —— 因此下面套了幂等守卫：
--   已存在则打印跳过，不存在才建，任何情况下都不会静默漏建。
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
