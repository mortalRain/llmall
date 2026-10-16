-- ============================================================================
-- 04 · 余额变动流水表（llmall-user 库）· 资金幂等与流水
--
-- ============================ 一、功能 ============================
-- 新建 llmall-user.balance_flow（余额变动流水表）：把每一次余额变动记成一条带幂等键的流水，
-- 让重复提交在 DB 层被唯一键顶掉，并留下可与 user.balance 对账的资金账本。
--
-- ============================ 二、背景 ============================
--  ① 余额扣款没有 DB 幂等键：上游重试、Feign 重发、并发双击都只能靠 Redis 锁挡，
--     锁一过期或进程一重启就失去保护 → 重复扣款，事后无法回答"钱为什么少了"；
--  ② 没有资金流水：余额只有一个当前值，出问题时没有任何可对账的账本。
--
-- ============================ 三、做法 ============================
-- 每一次余额变动都在同一个本地事务里写一条流水，并以 (biz_type, biz_no) 做唯一键：
--   · 同一业务号重复到达 → 唯一键顶掉 → 第二次不再扣款（幂等）；
--   · 流水自带 balance_after，可与 user.balance 对账：
--       SELECT SUM(change_amount) FROM balance_flow WHERE user_id = ?  ⇔ 余额的净变动
--   · 出账为负、入账为正，禁止 0（0 元流水没有意义，只会污染幂等键）。
--
-- 幂等键的口径（biz_no 由调用方给出，见各服务的调用点）：
--   DEDUCT / PAY:<支付单号>:<金额>                     订单余额支付（按订单维度确定：重试落同一键，不会重复扣款）
--   DEDUCT / VOUCHER_BUY_<券id>_<用户id>               普通付费券购买（一人一张，天然一次）
--   DEDUCT / VOUCHER-SECKILL-<token>                   秒杀付费券收款（token 即一次抢购）
--   REFUND / ORDER_REFUND:<订单id>                     订单退款入账
--   REFUND / PAYFAIL:<扣款业务号>                      支付流程失败后的退款补偿
--   REFUND / SECKILL_REFUND:<token>                    秒杀落库失败退款
--   REFUND / VOUCHER_REFUND_<券id>_<用户id>_<尝试uuid> 领券失败退款（按次唯一）
--
-- 【注意】扣款键必须是确定的（同一逻辑动作恒定），退款键必须与扣款键不同值：
--   两者共用一个键时，并发的第二个请求会因为"扣款被唯一键跳过但仍返回成功"而误判为自己付过钱，
--   它的失败补偿就会把第一个请求已成功的扣款退回去（用户白拿商品/券）。
--
-- ============================ 四、影响面 ============================
-- 纯新增表：不改动任何既有表结构，不动存量数据；对未发布"写流水"版本的应用无影响。
--
-- ============================ 五、幂等性与回滚 ============================
-- 幂等：本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- 回滚：应用回退到"不写流水"的版本后执行 DROP TABLE `llmall-user`.`balance_flow`;
--   【注意】DROP 会丢失全部资金流水（唯一的对账依据），回滚前必须导出该表。
--
-- ============================ 六、前置依赖 ============================
--   1) llmall-user 库已存在（见 00-create-databases.sql）；
--   2) 应用侧需已发布"余额变动写流水"的版本，否则表建好后不会有数据写入。
--
-- ============================ 七、风险等级 ============================
-- 低 —— 纯新增表（CREATE TABLE IF NOT EXISTS），可 DROP 完整回滚，不影响存量数据。
--
-- 执行：mysql -uroot -p123 llmall-user < 04-balance-flow.sql
-- ============================================================================

USE `llmall-user`;

CREATE TABLE IF NOT EXISTS `balance_flow` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '用户 id',
  `change_amount` INT             NOT NULL COMMENT '变动金额（分）：正=入账，负=出账，不允许 0',
  `balance_after` INT             NOT NULL COMMENT '本次变动后的余额（分），用于与 user.balance 对账',
  `biz_type`      VARCHAR(16)     NOT NULL COMMENT '业务类型：DEDUCT 出账 / REFUND 入账',
  `biz_no`        VARCHAR(96)     NOT NULL COMMENT '业务号（订单号/支付单号/券 token 等），与 biz_type 组成幂等键',
  `remark`        VARCHAR(255)             DEFAULT NULL COMMENT '备注（失败原因、补偿场景等，便于人工核对）',
  `create_time`   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`, `biz_no`) COMMENT '幂等：同一业务号只允许一次资金变动（重复提交/消息重投天然幂等）',
  KEY `idx_user_time` (`user_id`, `create_time`) COMMENT '按用户查流水（对账、客服查账）'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '余额变动流水（资金账本，与 user.balance 同事务写入）';
