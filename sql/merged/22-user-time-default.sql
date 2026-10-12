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
-- =====================================================================

ALTER TABLE `llmall-user`.`user`
  MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- 校验：应输出两条 default=CURRENT_TIMESTAMP
-- SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
--   FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='llmall-user' AND TABLE_NAME='user'
--    AND COLUMN_NAME IN ('create_time','update_time');
