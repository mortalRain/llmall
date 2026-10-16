-- =====================================================================
-- 40-search.sql
-- 40 · 搜索域建表（llmall-search-service / llmall-search）
--
-- 执行：mysql -uroot -p < 40-search.sql
--
-- ============================ 一、功能 ============================
-- 在 llmall-search 库建出搜索域三张表：shop_search_doc（门店搜索读模型）、
-- search_hot_word（热词统计）、search_history（用户搜索历史）与各自的检索/幂等索引。
--
-- ============================ 二、背景 ============================
-- 读模型的定位（决定了它必须存在）：事件驱动本地视图 + MySQL 检索 + Redis 热词，
-- 即"不引入 ES"也能检索。现已接入 ES 7.17（跑在 Windows 宿主 + IK 分词，见 M8 施工单），
-- 但定位不变：
--   · 本表仍是权威读模型 —— ES 不可用时检索回落它，不是"ES 替代它"；
--     因此本表不能因为"反正有 ES"而停止维护；
--   · ES 索引 shop_search 由本表灌数（POST /search/index/rebuild），字段一一对应；
--   · 坐标 x/y 不在本脚本，见 41-search-geo.sql（ES 的 geo_point 与"附近"排序需要）。
-- 两个唯一键的必要性：
--   · search_hot_word 的 uk_word_date(word, stat_date)：热词按"每日词频"记录，
--     同一天同一词只应有一条；
--   · search_history 的 uk_user_word(user_id, keyword)：同一用户同一关键词只留一条 ——
--     去重交给数据库，避免"先查后插"在并发下插入重复行。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-search 库新增 3 张表（全部 CREATE TABLE IF NOT EXISTS，已存在则跳过）。
--   · 不受影响：任何库的既有表结构与数据 —— 本脚本不含 ALTER / INSERT / UPDATE / DELETE。
--   · 不受影响：llmall-search.consume_record 由 02-shared-infra.sql 建，
--     llmall-search 库不入 Seata（其 application.yaml 无 seata 配置），故没有 undo_log。
--   · 不受影响：ES —— 本脚本只建 MySQL 侧的读模型表，不建索引；
--     ES 索引由 POST /search/index/rebuild 从本表灌数（见"四、执行步骤"第 5 步）。
--   · 建完是空表：数据靠事件增量维护与全量重建写入，演示数据由 51-seed-more-data.sql 灌入。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行（不可跳过：llmall-search 库由它建，
--      库不存在时报 ERROR 1049 Unknown database）。
--   2. 执行：mysql -uroot -p < 40-search.sql
--   3. 核对（期望 3 行）：
--        SELECT TABLE_NAME FROM information_schema.TABLES
--         WHERE TABLE_SCHEMA = 'llmall-search'
--           AND TABLE_NAME IN ('shop_search_doc','search_hot_word','search_history');
--   4. 紧跟其后执行 41-search-geo.sql（给读模型补 x/y，不可跳过）。
--   5. 调接口灌数（在 10 / 50 / 51 灌完门店数据之后再做）：
--        POST /search/view/rebuild    —— 从门店数据重建本表
--        POST /search/index/rebuild   —— 把本表灌进 ES
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 llmall-search 库）；
--      02-shared-infra.sql（llmall-search.consume_record —— 读模型的增量更新靠它做幂等）。
--   2) 数据来源依赖：10-shop.sql 与 50 / 51-seed 脚本（门店数据），
--      否则恢复读模型时源表为空、检索结果为空。
--   3) 下游依赖：41-search-geo.sql（改本脚本建的 shop_search_doc，必须在其后）；
--      51-seed-more-data.sql（写 search_history / search_hot_word）。
--   4) 顺序反了会怎样：库不存在 → ERROR 1049；
--      41 先于本脚本 → 守卫查不到 shop_search_doc，ALTER 报 ERROR 1146；
--      consume_record（02）未建 → 事件消费无法判重，读模型可能被同一事件重复覆盖。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不读搜索读模型"的版本后执行：
--   DROP TABLE `llmall-search`.`search_history`;
--   DROP TABLE `llmall-search`.`search_hot_word`;
--   DROP TABLE `llmall-search`.`shop_search_doc`;
-- 【可逆，但有两处代价】
--   · 本表是派生数据（源数据在 llmall-shop.shop 与内容域），重新调
--     POST /search/view/rebuild 即可重建 → 原则上可逆；
--   · 重建丢失的是"只能从事件里得到的信息"：event_time（乱序消息的版本比较依据）
--     与 status 的即时值；重建期间若门店热度又被更新，只能用当时的源表值覆盖，
--     因此回滚后必须紧接着全量重建，不能只 DROP 就算了。
--   · search_hot_word 的按天词频是累积统计，DROP 后无法重建（源数据只在内存/Redis 里），
--     需要保留历史热词榜单时先导出：
--       mysqldump -uroot -p llmall-search search_hot_word search_history > backup-40-search.sql
--
-- ============================ 附、风险等级 ============================
-- 低 —— 三张表全部是 IF NOT EXISTS 新建，不改写任何存量数据，可 DROP 完整回滚；
--       且读模型属派生数据，能靠 /search/view/rebuild 重建。
--       判据：改错（重复执行、误删表）后可以自动恢复 —— 重跑本脚本 + 调重建接口即可，
--       不需要人工修数；唯一的例外是热词按天统计（已在上方写明导出方式）。
-- =====================================================================

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
