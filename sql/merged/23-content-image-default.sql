-- =====================================================================
-- 23-content-image-default.sql
-- 23 · llmall-content.blog.images 改为可空且默认空串
--
-- 执行：mysql -uroot -p < 23-content-image-default.sql
--
-- ============================ 一、功能 ============================
-- 把 llmall-content.blog.images 由 "VARCHAR(2048) NOT NULL 无默认值" 改为
-- "VARCHAR(2048) NULL DEFAULT ''"，使发布不带图片的评价也能插入成功。
--
-- ============================ 二、背景 ============================
-- 真实故障现象：前端"发布评价"不带图片时，INSERT 报
--     java.sql.SQLException: Field 'images' doesn't have a default value
--   → 接口 500，评价发不出去。而"评价 → 门店热度 → 榜单排序"是核心闭环，
--   这一个字段直接卡住了整条链路（与 22 号脚本的 create_time 是同一类问题：
--   老表沿用下来时漏了默认值，而本项目新表都写了默认值）。
-- 两层保障（互不替代）：
--   1) 应用层：BlogServiceImpl#publishBlog 对 images 做 blankToDefault("") 兜底；
--   2) 库层：本脚本把字段改成可空且默认空串，与其它表一致。
--   只做其一都不够：只改应用则手工补数/其它写路径仍会失败；
--   只改库则应用下发的 NULL 会与既有"空串"两种语义并存，统计时容易漏。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-content.blog 的 images 一列（MODIFY COLUMN），共 1 张表 1 列。
--   · 受影响：该列的可空性由 NOT NULL 变为 NULL —— 之后允许写入 NULL 值，
--     业务语义上"空串"与"NULL"都表示"无图片"，读取侧必须两者都当空处理。
--   · 不受影响：这一列的存量取值 —— MODIFY 只改列定义，已有的 images 值一个都不变；
--     本脚本不含 UPDATE / INSERT / DELETE。
--   · 不受影响：blog 表其它列与其它任何表；其它库（脚本用全限定表名，不切库）。
--   · 不受影响：llmall-user.user 时间列的同类问题由 22-user-time-default.sql 处理。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 30-content.sql 已执行（不可跳过：blog 表由它建；顺序反了会报表不存在，
--      守卫特意做成"表不存在就照旧报 1146"，不会静默跳过，见下方三分支说明）。
--   2. 执行：mysql -uroot -p < 23-content-image-default.sql
--   3. 校验（脚本末尾语句取消注释后执行）：
--        SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA='llmall-content' AND TABLE_NAME='blog' AND COLUMN_NAME='images';
--      期望 IS_NULLABLE = YES、COLUMN_DEFAULT = （空串）。
--   4. 回归验证（不再报错才算通过）：调一次"发布评价"接口且不带图片。
-- 幂等性：本脚本可重复执行。MODIFY COLUMN 重复执行不报错，这里再套一层守卫
--    （与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：先查 information_schema.COLUMNS
--    看 images 是否已是目标形态（varchar(2048) / 可空 / DEFAULT ''），已是则跳过。
--    守卫里额外查了一次 TABLES：表不存在时不静默跳过，而是照旧报 1146 错，
--    因为"静默跳过"会让顺序错误（23 跑在 30 前面）变成一次无声的空操作，比直接报错更难查。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 llmall-content 库）；
--      30-content.sql（建 blog 表，CREATE TABLE IF NOT EXISTS `blog` ... `images` VARCHAR(2048) NOT NULL）。
--   2) 执行顺序必须是 30 在前、23 在后 —— 这是本目录唯一一处"破数字序"的地方，
--      sql/merged/README.md 的执行清单已按此排序（第 5.9 步，紧跟 5.8 的 30）。
--      【顺序反了会怎样】在全新库上把 23 排在 30 前面，直接报
--      "Table 'llmall-content.blog' doesn't exist"（ERROR 1146），
--      评价发布接口在应用启动后依然 500。
--   3) 应用侧需已发布"发布评价"功能；50 / 51 的演示评价数据（写入 blog.images）应排在其后。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不依赖库默认值"的版本后执行（还原成 NOT NULL 无默认值）：
--   ALTER TABLE `llmall-content`.`blog`
--     MODIFY COLUMN `images` VARCHAR(2048) NOT NULL COMMENT '图片，多张以逗号分隔';
-- 【回滚有条件，不是无脑可执行】改回 NOT NULL 后不允许出现 NULL，
--   而本脚本生效期间写入的 NULL 会阻塞这条语句（报 Invalid use of NULL value）：
--   回滚前必须先确认没有 NULL，有就先补成空串：
--     SELECT COUNT(*) AS null_rows FROM `llmall-content`.`blog` WHERE `images` IS NULL;   -- 期望 0
--     -- 有值时的清理：UPDATE `llmall-content`.`blog` SET `images` = '' WHERE `images` IS NULL;
--   另外原列注释若与上面不同，回滚前先从执行本脚本前留下的信息里取回（或直接用备份还原）。
--   备份方式（执行本脚本前做）：
--     mysqldump -uroot -p llmall-content blog > backup-23-blog.sql
--
-- ============================ 附、风险等级 ============================
-- 中 —— 改写的是存量表 blog 的列定义，并且放宽了可空性：新定义一旦生效，写入的 NULL
--       会让"回滚到 NOT NULL"这条反向语句失败；判据：改错后不能自动回到原状
--       （需先清 NULL 或从备份恢复，要人工判断），故为中；
--       不改任何存量取值、不涉及资金与账号，不到高。
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
