-- =====================================================================
-- 23-content-image-default.sql
-- 修复：llmall-content.blog.images 是 NOT NULL 且无默认值
--
-- 背景（真实故障）：
--   前端"发布评价"不带图片时，INSERT 报
--     java.sql.SQLException: Field 'images' doesn't have a default value
--   → 接口 500，评价发不出去。而"评价 → 门店热度 → 榜单排序"是项目的核心闭环，
--   这个字段直接卡住了整条链路（同 22 号脚本的 create_time 问题，都是老表缺默认值）。
--
-- 两层修复：
--   1) 应用层：BlogServiceImpl#publishBlog 对 images 做 blankToDefault("") 兜底；
--   2) 库层：本脚本给字段补默认值，与其它表保持一致。
--
-- ⚠️ 依赖：本脚本改的是 llmall-content.blog，该表由 30-content.sql 建立
--    （CREATE TABLE IF NOT EXISTS `blog` ... `images` VARCHAR(2048) NOT NULL）。
--    因此**执行顺序必须是 30 在前、23 在后**，否则报 "Table 'llmall-content.blog' doesn't exist"。
--    （2026-10 审计：原 README 按数字大小把 23 排在 30 之前，属于顺序缺陷，已在新 README 里修正。）
--
-- 幂等性：本脚本可**重复执行**。MODIFY COLUMN 重复执行不报错，这里再套一层守卫
--    （与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：先查 information_schema.COLUMNS
--    看 images 是否已是目标形态（varchar(2048) / 可空 / DEFAULT ''），已是则跳过。
--    守卫里额外查了一次 TABLES：**表不存在时不静默跳过，而是照旧报 1146 错**，
--    因为"静默跳过"会让顺序错误（23 跑在 30 前面）变成一次无声的空操作，比直接报错更难查。
-- =====================================================================

SET @t := (SELECT COUNT(*) FROM information_schema.TABLES
           WHERE TABLE_SCHEMA = 'llmall-content' AND TABLE_NAME = 'blog');
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'llmall-content' AND TABLE_NAME = 'blog' AND COLUMN_NAME = 'images'
             AND NOT (COLUMN_TYPE = 'varchar(2048)' AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT = ''));
-- 守卫三分支（@t = 表是否存在，@c = 字段形态是否需要修）：
--   ① @t = 0（llmall-content.blog 还没建，说明 30-content.sql 没先跑）
--        → 跑一条必然报 1146 的语句，把"顺序错误"暴露出来，不静默空操作；
--   ② @t > 0 且 @c > 0（表在、字段形态不是目标形态）→ 执行原来那条 MODIFY；
--   ③ @t > 0 且 @c = 0（已是 VARCHAR(2048) NULL DEFAULT ''）→ 跳过。
SET @s := IF(@t = 0,
    'SELECT `images` FROM `llmall-content`.`blog` WHERE 1 = 0',
    IF(@c > 0,
       'ALTER TABLE `llmall-content`.`blog`
          MODIFY COLUMN `images` VARCHAR(2048) NULL DEFAULT '''' COMMENT ''图片，多张以逗号分隔（可空）''',
       'SELECT ''llmall-content.blog.images 已可空且默认空串，跳过'''));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 校验
-- SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='llmall-content' AND TABLE_NAME='blog' AND COLUMN_NAME='images';
