-- ============================================================
-- 41 · 搜索域补充字段：经纬度（为 ES 的 geo_point 与"附近"排序）
--
-- 背景：门店表（llmall-shop.shop）原本就有 x（经度）/y（纬度）与 idx_geo 索引，
--      但搜索读模型 shop_search_doc 当初"不上 ES"时没冗余这两列 ——
--      MySQL 版检索只用"关键词 + 热度"，不需要坐标。
--      现在检索改用 ES：geo_point 字段与 geo_distance 排序都需要坐标，
--      所以把 x/y 补进读模型（全量重建时从门店数据带过来）。
--
-- 执行：mysql -uroot -p123 llmall-search < 41-search-geo.sql
--      然后调 POST /search/view/rebuild 重建读模型，再 POST /search/index/rebuild 灌进 ES。
--
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS，所以这里用 information_schema 守卫
--      （与 03b-seata-server-1.5.2-upgrade.sql 同一套房规），重复执行不再报
--      "Duplicate column name"，而是打印一句"已存在，跳过"。
-- ============================================================

USE `llmall-search`;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_search_doc'
             AND COLUMN_NAME IN ('x', 'y'));
SET @s := IF(@c = 0,
    'ALTER TABLE `shop_search_doc`
       ADD COLUMN `x` DOUBLE DEFAULT NULL COMMENT ''经度（ES geo_point 用，MySQL 检索不参与排序）'' AFTER `address`,
       ADD COLUMN `y` DOUBLE DEFAULT NULL COMMENT ''纬度（ES geo_point 用）'' AFTER `x`',
    'SELECT ''shop_search_doc.x / y 已存在，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
