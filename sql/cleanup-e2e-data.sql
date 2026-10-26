-- =====================================================================
-- cleanup-e2e-data.sql
-- 清理 Jack 名下的 E2E / 冒烟测试数据，并把余额恢复到"本轮测试之前"的值
--
-- 执行（演示库，容器内 mysql）：
--   docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p123 < sql/cleanup-e2e-data.sql
-- 或：
--   mysql -uroot -p < sql/cleanup-e2e-data.sql
--
-- ============================ 一、识别口径 ============================
-- 测试订单：llmall-trade.order 中 user_id = Jack 且 client_token 非空的行。
--   E2E 造数统一带 client_token（e2e-... / UUID / ui-... / id-check-... 等），
--   种子订单（id 1900100000000000001~9）没有 client_token，因此不会被误删。
-- 关联数据：
--   · order_detail：order_id 命中测试订单；
--   · pay_order：biz_order_no 命中测试订单（余额支付的支付单）；
--   · balance_flow：
--       - PAY:<pay_order_id>:<金额>（余额扣款）
--       - ORDER_REFUND:<order_id>（退款）
--       - VOUCHER_BUY_8004_1（E2E 购买 10 号店付费券 8004 的扣款）
--   · address：user_id = Jack 且 contact LIKE 'E2E%' 或 notes LIKE '%e2e%'；
--   · user_voucher：user_id = Jack 且 voucher_id = 8004（E2E 购买；种子券 8104 是 8005，不动）。
-- 不动（种子数据）：
--   · 订单 1900100000000000001~9、地址 59~63 / 9001~9003、user_voucher 8101~8104、
--     shop_staff、用户 Jack/Rose/Hope 的账号行本身（只改 Jack.balance）。
--
-- ============================ 二、余额恢复算法 ============================
-- 不写死目标余额。先算出"将被删除的流水"之和 @test_flow_sum，再：
--   恢复值 = 当前余额 - @test_flow_sum
-- 例：当前 890400，待删流水合计 -84000 → 恢复值 974400（= 10-17 种子流水后的余额，
-- 也就是 10-26 本轮测试开始前的余额；可对照 balance_flow 最后一条非测试流水的 balance_after）。
-- 这样即使重复执行、或测试期间发生退款（DEDUCT + REFUND 成对），结果都自洽。
--
-- ============================ 三、范围说明 ============================
--   · 会删除测试订单/明细/支付单/地址/券/流水，并删除 shop.consume_record 中对应 ORDER_PAID
--     幂等占位（否则会留下指向已删订单的孤儿记录）；不删 message_log（outbox 审计日志）。
--   · 不修改 shop.sold / item.stock：它们是按支付事件累加/扣减的派生统计，
--     回滚需要按事件溯源重算，超出"清理测试订单"的范围；如需完全还原请重建演示库。
--   · 执行前建议先跑脚本顶部的 BEFORE 查询并留档；执行后输出 AFTER 对比。
--
-- ============================ 四、回滚 ============================
-- 本脚本是删除操作，无法用 SQL 一键回滚。执行前请备份：
--   mysqldump -uroot -p --databases llmall-trade llmall-user llmall-pay llmall-promotion > backup-before-cleanup.sql
-- 演示库也可直接重跑 sql/merged/50、51 恢复种子数据（会覆盖对应固定 id）。
-- =====================================================================

USE `llmall-trade`;

SET @jack_id = (SELECT id FROM `llmall-user`.`user` WHERE `username` = 'Jack' LIMIT 1);

-- ---------------------------------------------------------------------
-- 1) 盘点：测试订单 / 明细 / 支付单 / 地址 / 券 / 流水
-- ---------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_e2e_orders;
CREATE TEMPORARY TABLE tmp_e2e_orders (`id` BIGINT PRIMARY KEY);

INSERT INTO tmp_e2e_orders (`id`)
SELECT `id`
FROM `order`
WHERE `user_id` = @jack_id
  AND `client_token` IS NOT NULL
  AND `client_token` <> '';

DROP TEMPORARY TABLE IF EXISTS tmp_e2e_pay_orders;
CREATE TEMPORARY TABLE tmp_e2e_pay_orders (`id` BIGINT PRIMARY KEY);

INSERT INTO tmp_e2e_pay_orders (`id`)
SELECT p.`id`
FROM `llmall-pay`.`pay_order` p
JOIN tmp_e2e_orders o ON o.`id` = p.`biz_order_no`;

-- 【MySQL 限制】同一查询里不能多次打开同一张 TEMPORARY TABLE（ERROR 1137），
-- 所以先把盘点结果算进用户变量，再做汇总展示/断言。
SET @test_order_count = (SELECT COUNT(*) FROM tmp_e2e_orders);
SET @test_order_amount = (
    SELECT COALESCE(SUM(o.`total_fee`), 0)
    FROM tmp_e2e_orders t JOIN `order` o ON o.`id` = t.`id`
);
SET @test_order_details = (
    SELECT COUNT(*)
    FROM `order_detail` d JOIN tmp_e2e_orders t ON d.`order_id` = t.`id`
);
SET @test_pay_count = (SELECT COUNT(*) FROM tmp_e2e_pay_orders);
SET @test_address_count = (
    SELECT COUNT(*) FROM `llmall-user`.`address`
    WHERE user_id = @jack_id AND (contact LIKE 'E2E%' OR notes LIKE '%e2e%')
);
SET @test_voucher_count = (
    SELECT COUNT(*) FROM `llmall-promotion`.`user_voucher`
    WHERE user_id = @jack_id AND voucher_id = 8004
);

SELECT 'BEFORE' AS phase,
       @test_order_count                                          AS test_orders,
       @test_order_amount                                         AS test_order_amount_fen,
       @test_order_details                                        AS test_order_details,
       @test_pay_count                                            AS test_pay_orders,
       @test_address_count                                        AS test_addresses,
       @test_voucher_count                                        AS test_vouchers,
       (SELECT balance FROM `llmall-user`.`user` WHERE id = @jack_id)
                                                                  AS balance_before_cleanup;

-- 明细：待删除流水的合计与恢复值
SET @test_flow_sum = (
    SELECT COALESCE(SUM(f.`change_amount`), 0)
    FROM `llmall-user`.`balance_flow` f
    WHERE f.`user_id` = @jack_id
      AND (
            EXISTS (SELECT 1 FROM tmp_e2e_pay_orders p
                    WHERE f.`biz_no` LIKE CONCAT('PAY:', p.`id`, ':%'))
         OR EXISTS (SELECT 1 FROM tmp_e2e_orders o
                    WHERE f.`biz_no` = CONCAT('ORDER_REFUND:', o.`id`))
         OR (f.`biz_no` = 'VOUCHER_BUY_8004_1'
             AND EXISTS (SELECT 1 FROM `llmall-promotion`.`user_voucher` uv
                         WHERE uv.`user_id` = @jack_id AND uv.`voucher_id` = 8004))
      )
);

SET @balance_before = (SELECT `balance` FROM `llmall-user`.`user` WHERE `id` = @jack_id);
SET @balance_restored = @balance_before - @test_flow_sum;

SELECT 'FLOW' AS phase,
       @test_flow_sum AS test_flow_sum_fen,
       @balance_before AS balance_before_cleanup,
       @balance_restored AS balance_to_restore;

-- 逐项列出将被删除的测试流水（留档用，最多 50 条）
SELECT f.`id`, f.`change_amount`, f.`balance_after`, f.`biz_type`, f.`biz_no`, f.`create_time`
FROM `llmall-user`.`balance_flow` f
WHERE f.`user_id` = @jack_id
  AND (
        EXISTS (SELECT 1 FROM tmp_e2e_pay_orders p
                WHERE f.`biz_no` LIKE CONCAT('PAY:', p.`id`, ':%'))
     OR EXISTS (SELECT 1 FROM tmp_e2e_orders o
                WHERE f.`biz_no` = CONCAT('ORDER_REFUND:', o.`id`))
     OR (f.`biz_no` = 'VOUCHER_BUY_8004_1'
         AND EXISTS (SELECT 1 FROM `llmall-promotion`.`user_voucher` uv
                     WHERE uv.`user_id` = @jack_id AND uv.`voucher_id` = 8004))
  )
ORDER BY f.`id`
LIMIT 50;

-- ---------------------------------------------------------------------
-- 2) 清理：按 id 精确删除（顺序：事件占位 → 明细 → 订单 → 支付单 → 流水 → 券 → 地址）
-- ---------------------------------------------------------------------

-- 2.1 门店域 ORDER_PAID 幂等占位（biz_id 是订单 id 字符串）
DELETE c
FROM `llmall-shop`.`consume_record` c
JOIN tmp_e2e_orders o ON c.`biz_id` = CAST(o.`id` AS CHAR)
WHERE c.`biz_type` = 'ORDER_PAID';

-- 2.2 交易域占位（当前 E2E 未产生，保留以保证脚本对历史数据也有效）
DELETE c
FROM `llmall-trade`.`consume_record` c
JOIN tmp_e2e_orders o ON c.`biz_id` = CAST(o.`id` AS CHAR)
WHERE c.`biz_type` IN ('ORDER_CREATED', 'PAY_SUCCESS', 'ORDER_VERIFIED');

-- 2.3 订单明细与订单
DELETE d
FROM `llmall-trade`.`order_detail` d
JOIN tmp_e2e_orders o ON d.`order_id` = o.`id`;

DELETE o
FROM `llmall-trade`.`order` o
JOIN tmp_e2e_orders t ON o.`id` = t.`id`;

-- 2.4 支付单
DELETE p
FROM `llmall-pay`.`pay_order` p
JOIN tmp_e2e_pay_orders t ON p.`id` = t.`id`;

-- 2.5 余额流水（必须在更新 user.balance 之前删除）
DELETE f
FROM `llmall-user`.`balance_flow` f
WHERE f.`user_id` = @jack_id
  AND (
        EXISTS (SELECT 1 FROM tmp_e2e_pay_orders p
                WHERE f.`biz_no` LIKE CONCAT('PAY:', p.`id`, ':%'))
     OR EXISTS (SELECT 1 FROM tmp_e2e_orders o
                WHERE f.`biz_no` = CONCAT('ORDER_REFUND:', o.`id`))
     OR (f.`biz_no` = 'VOUCHER_BUY_8004_1'
         AND EXISTS (SELECT 1 FROM `llmall-promotion`.`user_voucher` uv
                     WHERE uv.`user_id` = @jack_id AND uv.`voucher_id` = 8004))
  );

-- 2.6 E2E 购买的用户券（种子券 8104 是 voucher 8005，不受影响）
DELETE uv
FROM `llmall-promotion`.`user_voucher` uv
WHERE uv.`user_id` = @jack_id
  AND uv.`voucher_id` = 8004;

-- 2.7 E2E 地址（种子地址 contact 不是 E2E*，notes 不含 e2e）
DELETE a
FROM `llmall-user`.`address` a
WHERE a.`user_id` = @jack_id
  AND (a.`contact` LIKE 'E2E%' OR a.`notes` LIKE '%e2e%');

-- 2.8 余额恢复（用流水汇总，不写死；Jack 不存在或计算为空时不动）
UPDATE `llmall-user`.`user`
SET `balance` = @balance_restored
WHERE `id` = @jack_id
  AND @balance_restored IS NOT NULL;

-- ---------------------------------------------------------------------
-- 3) 清理后核对：余额 / 订单数 / 地址数 / 券数
-- ---------------------------------------------------------------------
SELECT 'AFTER' AS phase,
       (SELECT COUNT(*) FROM `order` WHERE user_id = @jack_id)                    AS jack_orders,
       (SELECT COUNT(*) FROM `order` WHERE user_id = @jack_id
         AND client_token IS NOT NULL AND client_token <> '')                    AS jack_test_orders_left,
       (SELECT COUNT(*) FROM `llmall-user`.`address` WHERE user_id = @jack_id)   AS jack_addresses,
       (SELECT COUNT(*) FROM `llmall-promotion`.`user_voucher` WHERE user_id = @jack_id)
                                                                                 AS jack_vouchers,
       (SELECT balance FROM `llmall-user`.`user` WHERE id = @jack_id)            AS jack_balance,
       (SELECT COUNT(*) FROM `llmall-user`.`balance_flow` WHERE user_id = @jack_id)
                                                                                 AS jack_balance_flows,
       @balance_restored                                                         AS expected_balance;

-- 断言式核对：剩余测试流水对应的订单/支付单必须为 0（期望都是 0）
-- 同样先算进变量，避免同一查询多次打开 TEMPORARY TABLE
SET @residual_details = (
    SELECT COUNT(*) FROM `llmall-trade`.`order_detail`
    WHERE order_id IN (SELECT id FROM tmp_e2e_orders)
);
SET @residual_pay_orders = (
    SELECT COUNT(*) FROM `llmall-pay`.`pay_order`
    WHERE biz_order_no IN (SELECT id FROM tmp_e2e_orders)
);
SET @residual_shop_records = (
    SELECT COUNT(*) FROM `llmall-shop`.`consume_record`
    WHERE biz_type = 'ORDER_PAID' AND biz_id IN (SELECT CAST(id AS CHAR) FROM tmp_e2e_orders)
);

SELECT 'RESIDUAL' AS phase,
       @residual_details      AS orphan_details,
       @residual_pay_orders   AS orphan_pay_orders,
       @residual_shop_records AS orphan_shop_consume_records;

-- 余额一致性：最后一条剩余流水的 balance_after 应等于当前余额
SELECT 'BALANCE_CHECK' AS phase,
       (SELECT balance FROM `llmall-user`.`user` WHERE id = @jack_id) AS current_balance,
       (SELECT balance_after FROM `llmall-user`.`balance_flow`
         WHERE user_id = @jack_id ORDER BY id DESC LIMIT 1)           AS last_flow_balance_after;
