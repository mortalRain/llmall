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
-- =====================================================================

ALTER TABLE `llmall-content`.`blog`
  MODIFY COLUMN `images` VARCHAR(2048) NULL DEFAULT '' COMMENT '图片，多张以逗号分隔（可空）';

-- 校验
-- SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='llmall-content' AND TABLE_NAME='blog' AND COLUMN_NAME='images';
