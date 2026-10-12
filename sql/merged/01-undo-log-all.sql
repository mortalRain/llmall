-- ============================================================
-- 01 · 为所有业务库创建 Seata AT 模式必需的 undo_log 表
-- 执行时机：M0（部署 Seata 时）；M4/M5 做分布式事务前必须就绪
-- ============================================================

USE `llmall-user`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id`     BIGINT       NOT NULL COMMENT 'branch transaction id',
  `xid`           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
  `context`       VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` LONGBLOB     NOT NULL COMMENT 'rollback info',
  `log_status`    INT          NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created`   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  `log_modified`  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-shop`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-item`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-cart`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-trade`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-pay`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-promotion`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE `llmall-content`;
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` BIGINT NOT NULL, `xid` VARCHAR(128) NOT NULL, `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL, `log_status` INT NOT NULL,
  `log_created` DATETIME(6) NOT NULL, `log_modified` DATETIME(6) NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';
