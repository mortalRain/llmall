-- =====================================================================
-- 10-shop.sql
-- 10 · 门店域建表（llmall-shop-service / llmall-shop）
--
-- 表结构由点评的 tb_shop、tb_shop_type 改造而来（改造点见下）。
--
-- 执行：mysql -uroot -p < 10-shop.sql
--
-- ============================ 一、功能 ============================
-- 在 llmall-shop 库建出门店域三张表：shop（门店）、shop_type（门店类型）、
-- shop_stat（门店访问统计），并建好热度、地理、类型三个索引。
--
-- ============================ 二、背景 ============================
-- 原商城是纯电商，没有任何"门店"概念：商品的归属是分类而不是门店，
-- 于是"门店详情页""附近门店""门店热度榜"这三条本地生活主链路都无表可用。
-- 本脚本在点评表的基础上补齐这些能力，并为三处容易踩错的地方定型：
--   ① 补 hot_score（热度分，由评价事件异步更新）与 hot_update_time / last_review_time：
--      没有热度分就无法做"榜单排序"，没有 last_review_time 就无法做时间衰减
--      （衰减因子 = 1/(1+Δ天/7)，锚点就是这个字段）；
--   ② 补 status（营业状态）：缺它则下架门店仍出现在列表与搜索里；
--   ③ 去掉原表的 ZEROFILL（MySQL 8 已不推荐）：保留会在严格模式下引发兼容问题；
--   ④ 补地理与热度索引（idx_geo / idx_hot / idx_type）：8.8 万级门店量下，
--      没有 idx_geo 的"附近门店"与没有 idx_hot 的榜单查询都会退化成全表扫。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-shop 库新增 3 张表（全部 CREATE TABLE IF NOT EXISTS，已存在则跳过）。
--   · 不受影响：任何库的既有表结构与数据 —— 本脚本不含 ALTER / INSERT / UPDATE / DELETE。
--   · 不受影响：llmall-shop 库里的 undo_log / consume_record / message_log，
--     分别由 01-undo-log-all.sql / 02-shared-infra.sql / 05-shop-message-log.sql 建。
--   · 建完是空表：门店数据要靠 50-seed-local-life-data.sql（演示数据）、
--     51-seed-more-data.sql（补齐 8 个类型）或 99-migrate-dianping-data.sql（从点评迁移）灌入。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行（不可跳过：llmall-shop 库由它建，
--      库不存在时报 ERROR 1049 Unknown database）。
--   2. 执行：mysql -uroot -p < 10-shop.sql
--   3. 核对（期望 3 行）：
--        SELECT TABLE_NAME FROM information_schema.TABLES
--         WHERE TABLE_SCHEMA = 'llmall-shop' AND TABLE_NAME IN ('shop','shop_type','shop_stat');
--   4. 核对地理与热度索引已建（缺失会让"附近门店"与榜单排序全表扫）：
--        SHOW INDEX FROM `llmall-shop`.`shop`;   -- 期望有 idx_type / idx_hot / idx_geo
--   5. （可选，需要门店数据时执行）脚本末尾"数据迁移"段：先建好 dianping_src 库并导入点评的
--      dianping-src.sql，再取消注释执行 —— 与 99-migrate-dianping-data.sql 作用重叠，
--      二者选一即可（99 用 ON DUPLICATE KEY UPDATE，可重复执行，推荐用 99）。
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 llmall-shop 库）。
--   2) 下游依赖：11-item-alter.sql（item.shop_id 指向本表的 id）、50 / 51 / 99
--      （演示与迁移数据全部写入 shop / shop_type）都必须在它之后。
--   3) 顺序反了会怎样：先跑 11/50/51/99 再跑本脚本 → 前面每个脚本都在 USE llmall-shop
--      或插数据时报 ERROR 1146 Table 'llmall-shop.shop' doesn't exist，且这些脚本大多是
--      "先删后插"，会在半途留下不完整的数据状态。
--   4) 若走第 5 步的点评迁移，dianping_src 库必须先存在且已导入数据，否则 SELECT 端
--      报 ERROR 1146。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不读门店表"的版本后执行：
--   DROP TABLE `llmall-shop`.`shop_stat`;
--   DROP TABLE `llmall-shop`.`shop`;
--   DROP TABLE `llmall-shop`.`shop_type`;
-- 【注意】DROP 是"结构 + 数据"一起丢，且本脚本一旦被 50 / 51 / 99 灌过数据就不再可逆：
--   · 只建表还没灌数据 → 直接 DROP 属可逆（重跑本脚本即恢复）；
--   · 已灌演示数据 → 重跑 10 + 50 + 51 可还原（数据是脚本生成的，可重建）；
--   · 已灌真实迁移数据（99）→ 重跑 99 依赖 dianping_src 仍在，否则数据不可恢复，
--     DROP 前必须先 mysqldump -uroot -p llmall-shop shop shop_type shop_stat > backup-10-shop.sql
--   另注意：shop.id 被 llmall-item.item.shop_id 与 llmall-trade.order.shop_id 引用，
--   DROP 后这些列会变成指向不存在门店的悬空值（不会报错，只会让"按门店查商品/订单"查不到）。
--
-- ============================ 附、风险等级 ============================
-- 低 —— 纯新增表（CREATE TABLE IF NOT EXISTS），不改写任何存量数据，可 DROP 完整回滚。
--       判据：即使建错库/重复执行，最坏结果是空表或跳过；表内数据的丢失风险来自
--       后续的 50/51/99，不属于本脚本，故按"改错后能自动恢复"定低。
-- =====================================================================

USE `llmall-shop`;

CREATE TABLE IF NOT EXISTS `shop` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`            VARCHAR(128)    NOT NULL COMMENT '门店名称',
  `type_id`         BIGINT UNSIGNED NOT NULL COMMENT '门店类型 id',
  `images`          VARCHAR(1024)   NOT NULL COMMENT '门店图片，多张以逗号分隔',
  `area`            VARCHAR(128)             DEFAULT NULL COMMENT '商圈',
  `address`         VARCHAR(255)    NOT NULL COMMENT '地址',
  `x`               DOUBLE UNSIGNED NOT NULL COMMENT '经度（GEO 附近门店用）',
  `y`               DOUBLE UNSIGNED NOT NULL COMMENT '纬度',
  `avg_price`       BIGINT UNSIGNED          DEFAULT NULL COMMENT '人均价格，单位分',
  `sold`            INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '销量',
  `comments`        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评价数',
  `score`           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评分 x10（45 表示 4.5 分）',
  `hot_score`       DECIMAL(12,4)   NOT NULL DEFAULT 0 COMMENT '热度分（时间衰减后，由评价事件异步更新）',
  `hot_update_time` DATETIME                 DEFAULT NULL COMMENT '热度最近计算时间',
  `last_review_time` DATETIME                DEFAULT NULL COMMENT '最近一次收到评价的时间（热度时间衰减的锚点）',
  `open_hours`      VARCHAR(32)              DEFAULT NULL COMMENT '营业时间，如 10:00-22:00',
  `status`          TINYINT         NOT NULL DEFAULT 1 COMMENT '1 营业中 2 休息中 3 已下线',
  `create_time`     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type` (`type_id`),
  KEY `idx_hot` (`hot_score`),
  KEY `idx_geo` (`x`, `y`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '门店表';

CREATE TABLE IF NOT EXISTS `shop_type` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(32)              DEFAULT NULL COMMENT '类型名称',
  `icon`        VARCHAR(255)             DEFAULT NULL COMMENT '图标',
  `sort`        INT UNSIGNED             DEFAULT NULL COMMENT '排序',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '门店类型表';

-- 门店访问统计（UV 用 Redis HyperLogLog 估算，每日落库一份）
CREATE TABLE IF NOT EXISTS `shop_stat` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `shop_id`     BIGINT UNSIGNED NOT NULL COMMENT '门店 id',
  `stat_date`   DATE            NOT NULL COMMENT '统计日期',
  `pv`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '访问次数',
  `uv`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '访问人数（HyperLogLog 估算值）',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_date` (`shop_id`, `stat_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '门店访问统计表';

-- ------------------------------------------------------------
-- 数据迁移（把点评的门店数据搬过来）
-- 前提：先建好 dianping_src 库并导入点评的 dianping-src.sql
-- ------------------------------------------------------------
-- INSERT INTO `llmall-shop`.`shop_type` (id, name, icon, sort, create_time, update_time)
-- SELECT id, name, icon, sort, create_time, update_time FROM `dianping_src`.`tb_shop_type`;
--
-- INSERT INTO `llmall-shop`.`shop`
--   (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours, create_time, update_time)
-- SELECT id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours, create_time, update_time
-- FROM `dianping_src`.`tb_shop`;
