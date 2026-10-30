-- Maintenance window only: stop and drain ALL old promotion/user writers first.
-- Required order: 64-payment-recovery.sql, then this file, then the coordinated release.
-- Never use mysql --force; never mix old and new purchase writers.
-- Read docs/voucher-purchase-reliability.md. No business data is repaired here.
-- Explicit prerequisites: all three tables introduced by 64 must already exist.
SELECT COUNT(*) FROM `llmall-user`.balance_payment WHERE 1=0;
SELECT COUNT(*) FROM `llmall-promotion`.voucher_order_cancel WHERE 1=0;
SELECT COUNT(*) FROM `llmall-pay`.payment_attempt WHERE 1=0;

-- BEGIN LEGACY GUARD
CREATE TEMPORARY TABLE `llmall-promotion`.llmall_voucher_purchase_upgrade_guard (id INT PRIMARY KEY);
INSERT INTO `llmall-promotion`.llmall_voucher_purchase_upgrade_guard VALUES (1);
-- Prefixes are exact (underscores are not LIKE wildcards). Parse the voucher id
-- AFTER the whole prefix: BUY and REFUND have different lengths. The user id is
-- the ledger owner, not the trailing key text. Include every historical attempt.
-- A negative net without a held voucher is unresolved; a positive net is unsafe
-- even when a voucher is held. A held voucher includes used and expired vouchers.
INSERT INTO `llmall-promotion`.llmall_voucher_purchase_upgrade_guard
SELECT 1 FROM (
  SELECT user_id, voucher_id, SUM(change_amount) AS net_amount
  FROM (
    SELECT user_id, change_amount,
      SUBSTRING(biz_no, LENGTH('VOUCHER_BUY_')+1,
        LOCATE('_', biz_no, LENGTH('VOUCHER_BUY_')+1)-LENGTH('VOUCHER_BUY_')-1) AS voucher_id
    FROM `llmall-user`.balance_flow
    WHERE biz_type='DEDUCT' AND LEFT(biz_no, LENGTH('VOUCHER_BUY_'))='VOUCHER_BUY_'
    UNION ALL
    SELECT user_id, change_amount,
      SUBSTRING(biz_no, LENGTH('VOUCHER_REFUND_')+1,
        LOCATE('_', biz_no, LENGTH('VOUCHER_REFUND_')+1)-LENGTH('VOUCHER_REFUND_')-1) AS voucher_id
    FROM `llmall-user`.balance_flow
    WHERE biz_type='REFUND' AND LEFT(biz_no, LENGTH('VOUCHER_REFUND_'))='VOUCHER_REFUND_'
  ) legacy_flows
  GROUP BY user_id, voucher_id
) legacy_net
LEFT JOIN `llmall-promotion`.user_voucher uv
  ON uv.user_id=legacy_net.user_id AND uv.voucher_id=legacy_net.voucher_id
WHERE legacy_net.net_amount>0 OR (uv.id IS NULL AND legacy_net.net_amount<>0)
LIMIT 1;
DROP TEMPORARY TABLE `llmall-promotion`.llmall_voucher_purchase_upgrade_guard;
-- END LEGACY GUARD

-- Additive only. Snowflake id is allocated by the application, never AUTO_INCREMENT.
CREATE TABLE IF NOT EXISTS `llmall-promotion`.`voucher_purchase` (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  voucher_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount INT NOT NULL COMMENT 'Amount in cents; zero is allowed',
  shop_id BIGINT DEFAULT NULL,
  expire_time DATETIME DEFAULT NULL,
  state VARCHAR(16) NOT NULL COMMENT 'ACTIVE/ISSUED/CANCELING/CANCELED',
  user_voucher_id BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  next_check_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_request (user_id, request_id),
  KEY idx_user_voucher_state (user_id, voucher_id, state),
  KEY idx_recovery (next_check_time, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
