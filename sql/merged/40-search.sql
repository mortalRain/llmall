-- ============================================================
-- 40 · 搜索域（llmall-search-service / llmall-search）
-- 说明：读模型 shop_search_doc 最初按「不引入 ES」的方案建的（事件驱动本地视图 + MySQL 检索 + Redis 热词）。
--      现已接入 ES 7.17（跑在 Windows 宿主 + IK 分词，见 M8 施工单）：
--        · 本表仍是**权威读模型**（ES 不可用时检索回落它，不是"ES 替代它"）；
--        · ES 索引 shop_search 由本表灌数（POST /search/index/rebuild），字段一一对应；
--        · 坐标 x/y 见 41-search-geo.sql（ES 的 geo_point 与"附近"排序需要）。
-- ============================================================

USE `llmall-search`;

-- 门店搜索视图（由 shop.hot.changed 事件增量维护 + 可全量重建）
CREATE TABLE IF NOT EXISTS `shop_search_doc` (
  `shop_id`     BIGINT UNSIGNED  NOT NULL COMMENT '门店 id',
  `name`        VARCHAR(128)     NOT NULL COMMENT '门店名称',
  `type_id`     BIGINT UNSIGNED  NOT NULL COMMENT '门店类型 id',
  `type_name`   VARCHAR(32)               DEFAULT NULL COMMENT '门店类型名称（冗余，避免检索时再关联）',
  `area`        VARCHAR(128)              DEFAULT NULL COMMENT '商圈',
  `address`     VARCHAR(255)     NOT NULL COMMENT '地址',
  `avg_price`   BIGINT UNSIGNED           DEFAULT NULL COMMENT '人均价格（分）',
  `score`       INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '评分 ×10',
  `comments`    INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '评价数',
  `sold`        INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '销量',
  `hot_score`   DECIMAL(12,4)    NOT NULL DEFAULT 0 COMMENT '热度分（排序主键）',
  `status`      TINYINT          NOT NULL DEFAULT 1 COMMENT '1 营业中 3 已下线',
  `event_time`  DATETIME                  DEFAULT NULL COMMENT '最近一次事件时间（用于乱序消息的版本比较）',
  `update_time` TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`shop_id`),
  KEY `idx_type_hot` (`type_id`, `hot_score`),
  KEY `idx_hot` (`hot_score`),
  KEY `idx_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '门店搜索视图表';

CREATE TABLE IF NOT EXISTS `search_hot_word` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `word`        VARCHAR(64)     NOT NULL COMMENT '热词',
  `search_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '搜索次数',
  `stat_date`   DATE                     DEFAULT NULL COMMENT '统计日期（按天滚动）',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word_date` (`word`, `stat_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '搜索热词统计表';

CREATE TABLE IF NOT EXISTS `search_history` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `keyword`     VARCHAR(64)     NOT NULL,
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `create_time` DESC),
  UNIQUE KEY `uk_user_word` (`user_id`, `keyword`) COMMENT '同一用户同一关键词只留一条：去重交给数据库，避免先查后插的并发重复'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户搜索历史表';
