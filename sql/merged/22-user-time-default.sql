-- =====================================================================
-- 22-user-time-default.sql
-- 22 · llmall-user.user 的 create_time / update_time 补默认值
--
-- 执行：mysql -uroot -p < 22-user-time-default.sql
--
-- ============================ 一、功能 ============================
-- 把 llmall-user.user 的 create_time / update_time 改成
-- NOT NULL DEFAULT CURRENT_TIMESTAMP（update_time 另加 ON UPDATE CURRENT_TIMESTAMP），
-- 使 INSERT 不带这两列时也能成功。
--
-- ============================ 二、背景 ============================
-- 真实故障现象：短信验证码登录带"未注册自动注册"，首次用新手机号登录时插入 user 报
--     java.sql.SQLException: Field 'create_time' doesn't have a default value
-- 当前状态：user 表沿用课程老库，两列是 NOT NULL 且无默认值；而本项目 sql/merged 里
--   新建的表都写了 DEFAULT CURRENT_TIMESTAMP（共 20+ 张），只有这一张漏了 ——
--   属于"新老库混用"时最容易漏掉的坑。
-- 两层保障（互不替代）：
--   1) 应用层：UserServiceImpl#loginByCode 显式 setCreateTime/setUpdateTime，
--      不依赖库的默认值 —— 保证应用代码可移植到任何库；
--   2) 库层：本脚本给老表补上默认值，与其它表一致 —— 保证"任何绕过应用直接 INSERT 的
--      场景（数据修复、演示数据脚本、手工补数）都不会因为漏填时间列而失败"。
--   只做其一都不够：只改应用则手工补数仍会失败；只改库则应用换库后仍有风险。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-user.user 两列的定义（MODIFY COLUMN），共 1 张表 2 列。
--   · 不受影响：这两列的存量取值 —— MODIFY 只改"列定义"，既有的 create_time / update_time
--     值一个都不变；本脚本不含 UPDATE / INSERT / DELETE。
--   · 不受影响：user 表其它列（含 password / balance / pay_password）与其它任何表。
--   · 不受影响：其它库 —— 本脚本用全限定表名 `llmall-user`.`user`，不切库、不碰别的库。
--   · 不受影响：llmall-content.blog.images 的同类问题由 23-content-image-default.sql 处理。
--
-- ============================ 四、执行步骤 ============================
--   1. 先确认将要改的就是目标列（不可跳过：MODIFY 会覆盖列定义，改错列会丢注释或类型）：
--        SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
--          FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA='llmall-user' AND TABLE_NAME='user'
--           AND COLUMN_NAME IN ('create_time','update_time');
--   2. 执行：mysql -uroot -p < 22-user-time-default.sql
--   3. 校验（脚本末尾的语句取消注释后执行；应输出两条 default=CURRENT_TIMESTAMP）：
--        SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
--          FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA='llmall-user' AND TABLE_NAME='user'
--           AND COLUMN_NAME IN ('create_time','update_time');
--   4. 回归验证（不再报错才算通过）：用一个新手机号走一次短信登录自动注册。
-- 幂等性：本脚本可重复执行。MODIFY COLUMN 本身重复执行不会报错，但每次都要求 MySQL
--   比对全表定义；这里再套一层守卫（与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：
--   先查 information_schema.COLUMNS 看两列是否已经是目标形态，已是则跳过、不是才执行。
--   判据（目标形态）：create_time = datetime / NOT NULL / DEFAULT CURRENT_TIMESTAMP；
--                    update_time = datetime / NOT NULL / DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP。
--   老库（无默认值，COLUMN_DEFAULT 为 NULL）上 @c > 0，执行的仍是原来那条 MODIFY，
--   效果与首次执行完全一致。
--
-- ============================ 五、前置依赖 ============================
--   1) 上级目录 sql/llmall-user.sql（建 llmall-user 库与 user 表）。
--   2) 与 21-user-alter.sql 改的是同一张表的不同列，可任意先后；
--      50-seed-local-life-data.sql（演示账号 UPDATE user）、51（写 user_sign / 读 user）应在其后。
--   3) 应用侧需已发布"自动注册"版本，否则本脚本改的默认值暂时不会被执行到（不影响正确性）。
--   4) 顺序反了会怎样：user 表不存在 → 守卫查不到这两列（@c 判定后）仍去执行 MODIFY，
--      报 ERROR 1146 Table 'llmall-user.user' doesn't exist；
--      本脚本漏跑就发布应用 → 新手机号登录在自动注册那一步报
--      Field 'create_time' doesn't have a default value，表现为"新用户登录必然失败"。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"显式 setCreateTime/setUpdateTime"的版本后执行（还原成"NOT NULL 且无默认值"）：
--   ALTER TABLE `llmall-user`.`user`
--     MODIFY COLUMN `create_time` DATETIME NOT NULL COMMENT '创建时间',
--     MODIFY COLUMN `update_time` DATETIME NOT NULL COMMENT '更新时间';
-- 可逆性说明：两列的可空性（NOT NULL）与类型（DATETIME）在改造前后完全一致，
--   只是默认值不同 —— 反向 MODIFY 即可恢复原定义，且存量取值在任何一步都不会被改写。
-- 【唯一需要先记录的东西】列注释：下面的回滚语句按本脚本的目标形态写了 COMMENT，
--   若原表的列注释与之不同，回滚前先从执行本脚本前留下的信息里取回原始 COMMENT，
--   或直接用备份还原（否则回滚会把注释也一起改成上面这两句）。
--   执行本脚本前建议留底：SHOW FULL COLUMNS FROM `llmall-user`.`user`;
--   完整备份方式：mysqldump -uroot -p llmall-user user > backup-22-user.sql
--
-- ============================ 附、风险等级 ============================
-- 低 —— 只改两列的定义（默认值），类型与可空性不变、存量取值一个都不动，
--       且有一条确定的反向 MODIFY 可以还原成原定义；判据：改错后能靠脚本本身
--       自动恢复（不依赖备份、不需要人工清数据），符合"可自动恢复即为低"。
-- =====================================================================

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'llmall-user' AND TABLE_NAME = 'user'
             AND (   (COLUMN_NAME = 'create_time'
                      AND NOT (COLUMN_TYPE = 'datetime' AND IS_NULLABLE = 'NO'
                               AND COLUMN_DEFAULT = 'CURRENT_TIMESTAMP'))
                  OR (COLUMN_NAME = 'update_time'
                      AND NOT (COLUMN_TYPE = 'datetime' AND IS_NULLABLE = 'NO'
                               AND COLUMN_DEFAULT = 'CURRENT_TIMESTAMP'
                               AND EXTRA LIKE '%on update CURRENT_TIMESTAMP%'))));
SET @s := IF(@c > 0,
    'ALTER TABLE `llmall-user`.`user`
       MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'',
       MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间''',
    'SELECT ''llmall-user.user.create_time / update_time 已有默认值，跳过''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 校验：应输出两条 default=CURRENT_TIMESTAMP
-- SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
--   FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='llmall-user' AND TABLE_NAME='user'
--    AND COLUMN_NAME IN ('create_time','update_time');
