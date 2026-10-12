-- ============================================================
-- 10 · 门店域（llmall-shop-service / llmall-shop）
-- 来源：点评 tb_shop、tb_shop_type
-- 改造点：① 补 hot_score（热度分，亮点 C）② 补 status（营业状态）
--        ③ 去掉原表的 ZEROFILL（MySQL 8 不推荐）④ 补地理与热度索引
-- ============================================================

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
