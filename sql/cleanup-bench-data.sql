-- 清理 P-01 压测数据（bench1~bench50，id 900001~900050）
-- 幂等：重复执行不报错；只删 id/用户区间，不碰种子与真实数据。
USE `llmall-trade`;

START TRANSACTION;

DELETE d FROM `llmall-trade`.`order_detail` d
  JOIN `llmall-trade`.`order` o ON o.id = d.order_id
 WHERE o.user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-trade`.`order`
 WHERE user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-pay`.`pay_order`
 WHERE biz_user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-user`.`balance_flow`
 WHERE user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-user`.`points_flow`
 WHERE user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-cart`.`cart`
 WHERE user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-promotion`.`user_voucher`
 WHERE user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-user`.`address`
 WHERE user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-user`.`user_info`
 WHERE user_id BETWEEN 900001 AND 900050;

DELETE FROM `llmall-user`.`user`
 WHERE id BETWEEN 900001 AND 900050;

-- 900013 原始 stock=176、sold=252（seed 公式）；非 bench 销量 3 单
UPDATE `llmall-item`.`item` SET stock = 173, sold = 255 WHERE id = 900013;
-- 900014 原始 stock=189、sold=299（seed 公式），未被 bench 下单
UPDATE `llmall-item`.`item` SET stock = 189, sold = 299 WHERE id = 900014;

COMMIT;

SELECT 'after' AS phase,
       (SELECT COUNT(*) FROM `llmall-user`.`user` WHERE id BETWEEN 900001 AND 900050) AS bench_users,
       (SELECT COUNT(*) FROM `llmall-user`.`address` WHERE user_id BETWEEN 900001 AND 900050) AS bench_addrs,
       (SELECT COUNT(*) FROM `llmall-trade`.`order` WHERE user_id BETWEEN 900001 AND 900050) AS bench_orders,
       (SELECT COUNT(*) FROM `llmall-pay`.`pay_order` WHERE biz_user_id BETWEEN 900001 AND 900050) AS bench_pays,
       (SELECT stock FROM `llmall-item`.`item` WHERE id = 900013) AS stock_900013,
       (SELECT sold FROM `llmall-item`.`item` WHERE id = 900013) AS sold_900013,
       (SELECT stock FROM `llmall-item`.`item` WHERE id = 900014) AS stock_900014;
