-- =====================================================================
-- 41-search-geo.sql
-- 41 · 搜索域读模型补经纬度（为 ES 的 geo_point 与"附近"排序）
--
-- 执行：mysql -uroot -p < 41-search-geo.sql
--
-- ============================ 一、功能 ============================
-- 给 llmall-search.shop_search_doc 补 x（经度）/ y（纬度）两列与对应注释，
-- 作为 ES 的 geo_point 字段与 geo_distance 排序的数据来源。
--
-- ============================ 二、背景 ============================
-- 门店表（llmall-shop.shop）本来就有 x / y 与 idx_geo 索引，
-- 但搜索读模型 shop_search_doc 建表时是"不引入 ES"的方案，没有冗余这两列 ——
-- MySQL 版检索只用"关键词 + 热度"，不需要坐标。
-- 检索改用 ES 后，geo_point 字段与"附近"/距离排序都需要坐标，
-- 因此把 x / y 补进读模型（全量重建时从门店数据带过来）。
-- 【不变量】读模型是"门店行的冗余快照"：凡是检索/排序会用到、而门店表里有的字段，
-- 都必须冗余进本表；漏一列就只能在查询时回表 join，读模型也就失去意义。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-search.shop_search_doc 新增 x / y 两列
--     （ADD COLUMN 会把存量读模型行一次性填充为 NULL）。
--   · 不受影响：该表既有列与行数据 —— 除新列的 NULL 填充外不改写任何已有值。
--   · 不受影响：llmall-shop.shop 的 x / y 与 idx_geo —— 本脚本只读写模型那张表。
--   · 不受影响：其它任何表与库。
--   · 【注意】新增列后旧数据是 NULL，ES 的"附近"排序在重建之前拿不到坐标：
--     必须按"四、执行步骤"第 3 步重建读模型，否则"附近门店"会全空。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 40-search.sql 已执行（不可跳过：shop_search_doc 由它建，
--      表不存在时守卫查不到列，ALTER 报 ERROR 1146）。
--   2. 执行：mysql -uroot -p < 41-search-geo.sql
--   3. 重建读模型与 ES 索引（不可跳过，否则 x / y 仍是 NULL）：
--        POST /search/view/rebuild    —— 从门店数据带出坐标，重建本表
--        POST /search/index/rebuild   —— 灌进 ES，geo_point 才有值
--   4. 核对（期望 2 行）：
--        SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA='llmall-search' AND TABLE_NAME='shop_search_doc'
--           AND COLUMN_NAME IN ('x','y');
-- 幂等：MySQL 不支持 ADD COLUMN IF NOT EXISTS，所以这里用 information_schema 守卫
--      （与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：列已存在则打印"已存在，跳过"，
--      不报 Duplicate column name、也不重复加列。
--
-- ============================ 五、前置依赖 ============================
--   1) 40-search.sql（建 shop_search_doc，必须在前，这是硬依赖）。
--   2) 10-shop.sql（llmall-shop.shop 的 x / y 是数据来源，重建读模型时读取）。
--   3) 顺序反了会怎样：41 先于 40 → 守卫查不到 shop_search_doc，
--      仍去 ALTER，报 ERROR 1146 Table 'llmall-search.shop_search_doc' doesn't exist；
--      10 未跑 → 结构正常，但重建后 x / y 仍是 NULL，"附近门店"排序不生效且不报错。
--   4) 本脚本必须排在 40 之后（README 第 5.12 步），因为读模型表由 40 建。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不读坐标"的版本后执行：
--   ALTER TABLE `llmall-search`.`shop_search_doc` DROP COLUMN `y`, DROP COLUMN `x`;
-- 【可逆】本表是派生数据：DROP 掉两列后重跑本脚本 + 调
--   POST /search/view/rebuild 即可恢复，不需要从备份还原。
--   回滚不影响 llmall-shop.shop 的 x / y（坐标的权威来源始终在门店表），
--   因此这次回滚不会造成任何"只能从备份找回"的数据损失。
--
-- ============================ 附、风险等级 ============================
-- 低 —— 只给一张派生表新增两列，不改写既有取值；判据：改错（漏执行、重复执行、
--       误 DROP 列）都能靠"重跑本脚本 + 调 /search/view/rebuild 重建"自动恢复，
--       源数据在 llmall-shop.shop 里始终完好，不存在不可逆的路径。
-- =====================================================================

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
