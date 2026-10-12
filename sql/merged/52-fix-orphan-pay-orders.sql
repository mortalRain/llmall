-- 修复：前端 Number() 精度丢失导致的"孤儿支付单"清理与退款
-- 背景：order.id 是 19 位雪花号，前端曾写 Number(order.id) 生成 bizOrderNo，
--       超过 2^53 后被四舍五入，支付单指向了一个不存在的订单号：
--       ① 支付成功也无法把订单改成已付款（表现为订单停在"待付款"）；
--       ② 加了支付前校验后直接报"订单不存在"。
-- 处理：把孤儿支付单关闭并退还已误扣的余额（保留记录作为审计痕迹）。

-- 1) 计算需要退还的金额（已支付但订单不存在的单子，且属于用户 1）
SET @refund = (
  SELECT IFNULL(SUM(p.amount), 0)
  FROM `llmall-pay`.`pay_order` p
  LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no
  WHERE o.id IS NULL AND p.status = 3 AND p.biz_user_id = 1
);

-- 2) 退还余额
UPDATE `llmall-user`.`user` SET balance = balance + @refund WHERE id = 1;

-- 3) 关闭孤儿支付单（status 2 = 已关闭），标注原因
UPDATE `llmall-pay`.`pay_order` p
LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no
SET p.status = 2,
    p.result_msg = 'ORPHAN: bizOrderNo 因前端 Number 精度丢失而无效，已关闭并退款',
    p.update_time = NOW()
WHERE o.id IS NULL;

-- 4) 自检
SELECT '退还金额(分)' t, @refund v
UNION ALL SELECT '剩余孤儿单', COUNT(*) FROM `llmall-pay`.`pay_order` p
  LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no WHERE o.id IS NULL
UNION ALL SELECT '用户1余额(分)', balance FROM `llmall-user`.`user` WHERE id = 1;
