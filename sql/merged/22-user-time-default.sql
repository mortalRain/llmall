-- =====================================================================
-- 22-user-time-default.sql
-- 修复：llmall-user.user 的 create_time / update_time 是 NOT NULL 且无默认值
--
-- 背景（真实故障）：
--   短信验证码登录带"未注册自动注册"，首次用新手机号登录时插入 user 报
--     java.sql.SQLException: Field 'create_time' doesn't have a default value
--   原因是 user 表沿用课程老库，建表时没给时间字段默认值；
--   而本项目 sql/merged 里新建的表都写了 DEFAULT CURRENT_TIMESTAMP（共 20+ 张），
--   只有这一张漏了，属于"新老库混用"时最容易漏掉的坑。
--
-- 两层修复：
--   1) 应用层：UserServiceImpl#loginByCode 显式 setCreateTime/setUpdateTime（不依赖库的默认值）
--   2) 库层：本脚本给老表补上默认值，保持与其它表一致（双保险）
--
-- 幂等性：本脚本可**重复执行**。MODIFY COLUMN 本身重复执行不会报错，但每次都要求 MySQL
--   比对全表定义；这里再套一层守卫（与 03b-seata-server-1.5.2-upgrade.sql 同一套房规）：
--   先查 information_schema.COLUMNS 看两列**是否已经是目标形态**，已是则跳过、不是才执行。
--   判据（目标形态）：create_time = datetime / NOT NULL / DEFAULT CURRENT_TIMESTAMP；
--                    update_time = datetime / NOT NULL / DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP。
--   老库（无默认值，COLUMN_DEFAULT 为 NULL）上 @c > 0，执行的仍是原来那条 MODIFY，
--   效果与首次执行完全一致。
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
