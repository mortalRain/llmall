-- Maintenance window only: stop/drain ALL old user/pay/trade/promotion writers first.
-- Run without mysql --force. A duplicate-key error below deliberately blocks unsafe upgrades.
-- Inspect docs/payment-reliability.md before running against existing data.
CREATE TEMPORARY TABLE `llmall-pay`.llmall_payment_upgrade_guard (id INT PRIMARY KEY);
INSERT INTO `llmall-pay`.llmall_payment_upgrade_guard VALUES (1);
-- Legacy PROCESSING/UNCERTAIN operations must be reconciled, never reset blindly.
INSERT INTO `llmall-pay`.llmall_payment_upgrade_guard
SELECT 1 FROM `llmall-pay`.pay_order
WHERE status=4 OR result_code='UNCERTAIN' LIMIT 1;
-- A legacy debit may have committed while pay_order rolled back to WAITING without an UNCERTAIN flag.
-- Changing to a V2 key in that state would debit a second time. Any nonmatching refund is unsafe.
INSERT INTO `llmall-pay`.llmall_payment_upgrade_guard
SELECT 1 FROM `llmall-pay`.pay_order p
JOIN `llmall-user`.balance_flow d ON d.biz_type='DEDUCT' AND d.biz_no LIKE CONCAT('PAY:',p.id,':%')
LEFT JOIN `llmall-user`.balance_flow r ON r.biz_type='REFUND' AND r.biz_no=CONCAT('PAYFAIL:',d.biz_no)
  AND r.user_id=d.user_id AND r.change_amount=-d.change_amount
WHERE p.status IN (0,1) AND r.id IS NULL LIMIT 1;
DROP TEMPORARY TABLE `llmall-pay`.llmall_payment_upgrade_guard;

-- Additive only: no existing balances, orders or historical flows are rewritten.
CREATE TABLE IF NOT EXISTS `llmall-user`.`balance_payment` (
  payment_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  amount INT NOT NULL,
  state VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (payment_key),
  KEY idx_user_time (user_id, create_time)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `llmall-promotion`.`voucher_order_cancel` (
  user_voucher_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_voucher_id, order_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `llmall-pay`.`payment_attempt` (
  id BIGINT NOT NULL,
  pay_order_id BIGINT NOT NULL,
  biz_order_no BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  amount INT NOT NULL,
  state VARCHAR(16) NOT NULL,
  notified BOOLEAN NOT NULL DEFAULT FALSE,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  next_check_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_payment (pay_order_id, id),
  KEY idx_recovery (next_check_time, state, notified)
) ENGINE=InnoDB;
