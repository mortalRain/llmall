-- ============================================================
-- 20 · 营销域（llmall-promotion-service / llmall-promotion）
-- 来源：点评 tb_voucher、tb_seckill_voucher、tb_voucher_order
-- 改造点（重要）：
--   ① 原 tb_seckill_voucher 的 begin/end_time 用了 '0000-00-00 00:00:00' 默认值，
--      MySQL 8 严格模式会直接报错 → 改为 NULL 默认。
--   ② 原 tb_voucher_order 是"券订单"，合并后【订单统一归 order-service】（llmall-order.order 加 order_type=2），
--      promotion 侧只保留"用户持有的券"（user_voucher），职责边界清晰：
--        交易归订单域，券的生命周期归营销域。
--   ③ points_flow 的 (biz_type, biz_id) 唯一索引 = 积分发放的幂等闸门（亮点 B 用）。
-- ============================================================

USE `llmall-promotion`;

CREATE TABLE IF NOT EXISTS `voucher` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `shop_id`      BIGINT UNSIGNED          DEFAULT NULL COMMENT '所属门店',
  `title`        VARCHAR(255)    NOT NULL COMMENT '代金券标题',
  `sub_title`    VARCHAR(255)             DEFAULT NULL COMMENT '副标题',
  `rules`        VARCHAR(1024)            DEFAULT NULL COMMENT '使用规则',
  `pay_value`    BIGINT UNSIGNED NOT NULL COMMENT '支付金额，单位分（200 表示 2 元）',
  `actual_value` BIGINT          NOT NULL COMMENT '抵扣金额，单位分',
  `type`         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 普通券 1 秒杀券',
  `status`       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 上架 2 下架 3 过期',
  `create_time`  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_shop` (`shop_id`),
  KEY `idx_type_status` (`type`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '优惠券表';

CREATE TABLE IF NOT EXISTS `seckill_voucher` (
  `voucher_id`  BIGINT UNSIGNED NOT NULL COMMENT '关联 voucher.id',
  `stock`       INT             NOT NULL COMMENT '库存（Redis 预扣减 + DB 异步扣减）',
  `begin_time`  TIMESTAMP       NULL DEFAULT NULL COMMENT '生效时间',
  `end_time`    TIMESTAMP       NULL DEFAULT NULL COMMENT '失效时间',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`voucher_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '秒杀券表';

-- 用户持有的券（券实例）：领取即生成，核销后置为已使用
CREATE TABLE IF NOT EXISTS `user_voucher` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '用户 id',
  `voucher_id`  BIGINT UNSIGNED NOT NULL COMMENT '券 id',
  `shop_id`     BIGINT UNSIGNED          DEFAULT NULL COMMENT '冗余门店 id，便于按门店核销',
  `order_id`    BIGINT                   DEFAULT NULL COMMENT '关联订单 id（券类订单）',
  `status`      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 未使用 2 已核销 3 已过期 4 已退款',
  `receive_time` TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  `use_time`    TIMESTAMP       NULL DEFAULT NULL COMMENT '核销时间',
  `expire_time` TIMESTAMP       NULL DEFAULT NULL COMMENT '过期时间',
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`) COMMENT '一人一单的数据库兜底（Redis 是第一道闸）',
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_order` (`order_id`),
  -- 按券维度统计已发放数量（voucher_id 单独查询用不上 uk_user_voucher 的前导列 user_id）
  KEY `idx_voucher` (`voucher_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例表';

-- 积分流水已移到 llmall-user（见 21-user-alter.sql）：
--   credits 是用户资产，流水必须与资产同库同事务，才能在一个本地事务里"加积分 + 记流水"。
--   放在营销域会导致"积分加了但流水没写"的跨库不一致，反而制造新的分布式事务。
