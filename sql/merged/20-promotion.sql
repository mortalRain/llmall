-- =====================================================================
-- 20-promotion.sql
-- 20 · 营销域建表（llmall-promotion-service / llmall-promotion）
--
-- 表结构由点评的 tb_voucher、tb_seckill_voucher、tb_voucher_order 改造而来。
--
-- 执行：mysql -uroot -p < 20-promotion.sql
--
-- ============================ 一、功能 ============================
-- 在 llmall-promotion 库建出营销域三张表：voucher（券定义）、seckill_voucher（秒杀券库存）、
-- user_voucher（用户持有的券实例，含一人一单的唯一键）。
--
-- ============================ 二、背景 ============================
--   ① 原 tb_seckill_voucher 的 begin/end_time 用了 '0000-00-00 00:00:00' 作为默认值，
--      MySQL 8 严格模式下建表就直接报 Invalid default value → 本表改为 NULL 默认，
--      语义仍是"未设置即不限制"，但不再依赖 MySQL 的零值日期。
--   ② 原 tb_voucher_order 是"券订单"，合并后订单统一归订单域（llmall-trade.order 用
--      order_type=2 表示门店套餐/团购券订单），promotion 侧只保留"用户持有的券"
--      （user_voucher），职责边界是：交易归订单域，券的生命周期归营销域。
--      两处都记订单，会出现"券核销了、订单还是待付款"这类对不上的状态。
--   ③ user_voucher 的 uk_user_voucher(user_id, voucher_id) = 一人一单的数据库兜底
--      （Redis 已购集合是第一道闸，唯一键是最后一道）；缺它时并发领取会重复发券。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-promotion 库新增 3 张表（全部 CREATE TABLE IF NOT EXISTS，已存在则跳过）。
--   · 不受影响：任何库的既有表结构与数据 —— 本脚本不含 ALTER / INSERT / UPDATE / DELETE。
--   · 不受影响：llmall-promotion 库里的 undo_log / consume_record，分别由
--     01-undo-log-all.sql / 02-shared-infra.sql 建。
--   · 不受影响：积分流水不在本库 —— credits 是用户资产，流水必须与资产同库同事务
--     （llmall-user.points_flow，由 21-user-alter.sql 建），详见本脚本末尾说明。
--   · 建完是空表：券数据要靠 51-seed-more-data.sql（演示数据）或业务写入。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行（不可跳过：llmall-promotion 库由它建，
--      库不存在时报 ERROR 1049 Unknown database）。
--   2. 执行：mysql -uroot -p < 20-promotion.sql
--   3. 核对（期望 3 行）：
--        SELECT TABLE_NAME FROM information_schema.TABLES
--         WHERE TABLE_SCHEMA = 'llmall-promotion'
--           AND TABLE_NAME IN ('voucher','seckill_voucher','user_voucher');
--   4. 核对一人一单的兜底唯一键存在（缺失会让并发领券重复发券）：
--        SHOW INDEX FROM `llmall-promotion`.`user_voucher`;   -- 期望有 uk_user_voucher
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 llmall-promotion 库）。
--   2) 数据上的关联：voucher.shop_id 指向 llmall-shop.shop.id（10-shop.sql 建表，
--      50/51 灌数据）；user_voucher.order_id 指向 llmall-trade.order.id（31-order-alter.sql）。
--      本脚本不建外键，所以顺序反了不会报错，只会让"按门店/按订单查券"查不到数据。
--   3) 顺序反了会怎样：库不存在 → ERROR 1049；11/31 未跑 → 结构正常但券与门店/订单关联为空。
--   4) 51-seed-more-data.sql 依赖本脚本建好的三张表，必须排在其后。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不读营销域表"的版本后执行：
--   DROP TABLE `llmall-promotion`.`user_voucher`;
--   DROP TABLE `llmall-promotion`.`seckill_voucher`;
--   DROP TABLE `llmall-promotion`.`voucher`;
-- 【注意】DROP 会连带丢掉"用户已持有的券"这一核心资产数据：
--   · 只建表还没写数据 → 直接 DROP 属可逆（重跑本脚本即恢复）；
--   · 已有用户券 → 不可逆，用户已购买的券记录消失，退款与核销都失去依据。
--     DROP 前必须导出：mysqldump -uroot -p llmall-promotion voucher seckill_voucher user_voucher \
--                                 > backup-20-promotion.sql
--   另外 user_voucher.order_id 指向订单，DROP 后订单侧仍显示"已购券"，两侧会长期不一致。
--
-- ============================ 附、风险等级 ============================
-- 低 —— 三张表全部是 IF NOT EXISTS 新建，不改写任何存量数据，可 DROP 完整回滚。
--       判据：改错（重复执行、误在别的库执行）只会跳过或新建空表，不存在"改坏存量数据"
--       的路径；真正的数据风险来自表建好之后的业务写入，不属于本脚本。
-- =====================================================================

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
  `status`       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 上架 2 下架',
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
  `status`      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 未使用 2 已使用 3 已过期（退款为改回 1，无 4 已退款）',
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
