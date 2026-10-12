-- ============================================================
-- 02 · 最终一致性基础设施表（亮点 B 的核心）
-- message_log   本地消息表：与业务同事务写入，保证"业务成功 → 消息一定发出"
-- consume_record 消费幂等表：靠唯一索引挡住重复投递（MQ 是 at-least-once，不保证不重复）
-- ============================================================

-- 说明：订单库沿用你环境中已存在的 llmall-trade（设计文档里的 llmall-order 只是命名建议，
--      这里改成 llmall-trade 以避免数据迁移与配置改动）
USE `llmall-trade`;

-- 本地消息表（每个"消息生产者"服务在自己的库里建自己的消息表）
CREATE TABLE IF NOT EXISTS `message_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型，如 ORDER_CREATED / ORDER_PAID / REVIEW_CREATED',
  `biz_id`        VARCHAR(64)  NOT NULL COMMENT '业务主键（订单号、评价 id 等）',
  `exchange`      VARCHAR(128) NOT NULL COMMENT '目标交换机',
  `routing_key`   VARCHAR(128) NOT NULL COMMENT '路由键',
  `payload`       TEXT         NOT NULL COMMENT '消息体（JSON）',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待发送 1 已投递 2 已完成 3 失败待人工',
  `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '已重试次数（指数退避）',
  `next_retry_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
  `error_msg`     VARCHAR(1024) DEFAULT NULL COMMENT '最近一次失败原因',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`),
  KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表（可靠消息最终一致性）';

-- 消费幂等表（每个"消息消费者"服务在自己的库里建一份）
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `biz_type`    VARCHAR(64) NOT NULL COMMENT '业务类型',
  `biz_id`      VARCHAR(64) NOT NULL COMMENT '业务主键',
  `consumer`    VARCHAR(64) NOT NULL COMMENT '消费方标识（服务名）',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`) COMMENT '唯一索引就是幂等闸门'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';

-- 其余消费者服务各自建一份（同一段 DDL，换库执行即可）
USE `llmall-user`;
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `biz_type` VARCHAR(64) NOT NULL, `biz_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(64) NOT NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';

USE `llmall-shop`;
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `biz_type` VARCHAR(64) NOT NULL, `biz_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(64) NOT NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';

USE `llmall-promotion`;
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `biz_type` VARCHAR(64) NOT NULL, `biz_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(64) NOT NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';

USE `llmall-search`;
CREATE TABLE IF NOT EXISTS `consume_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `biz_type` VARCHAR(64) NOT NULL, `biz_id` VARCHAR(64) NOT NULL,
  `consumer` VARCHAR(64) NOT NULL, `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_consume` (`biz_type`, `biz_id`, `consumer`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费幂等记录';
