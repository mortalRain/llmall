-- =====================================================================
-- 52-fix-orphan-pay-orders.sql
-- 52 · 清理"孤儿支付单"并退还误扣余额
--
-- 执行：mysql -uroot -p < 52-fix-orphan-pay-orders.sql
--
-- ============================ 一、功能 ============================
-- 把 llmall-pay.pay_order 里"指向不存在订单"的支付单关闭（status=2），
-- 并把其中已支付、属于用户 1 的金额退还到 llmall-user.user.balance；
-- 支付单记录保留（只改状态与 result_msg），作为审计痕迹。
--
-- ============================ 二、背景 ============================
-- 真实故障现象：order.id 是 19 位雪花号，前端曾写 Number(order.id) 生成 bizOrderNo，
--       超过 2^53 后被四舍五入，支付单指向了一个不存在的订单号：
--       ① 支付成功也无法把订单改成已付款（表现为订单停在"待付款"）；
--       ② 加了支付前校验后直接报"订单不存在"。
-- 当前的不变量：pay_order.biz_order_no 必须与 order.id 精确相等 ——
--   任何精度丢失、字符串化再转数字、前端 Number() 处理都会重新制造孤儿单；
--   本脚本只是把已经产生的孤儿单收口，不修复产生孤儿单的路径（那是应用侧的事）。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-pay.pay_order 的 status / result_msg / update_time
--     （所有"订单不存在"的支付单被改成已关闭）；llmall-user.user.balance（仅 id = 1）。
--   · 不受影响：正常支付单（biz_order_no 能匹配到订单的行完全不进 WHERE）。
--   · 不受影响：llmall-trade.order —— 本脚本只读它，一行都不改（订单本来就停在待付款）。
--   · 不受影响：其它任何表与库。
--   · 【注意】两段 UPDATE 的过滤条件口径不同，执行前必须看清：
--       退款段（第 1 步）= 孤儿单 AND status=3（已支付）AND biz_user_id=1；
--       关闭段（第 3 步）= 孤儿单，不带用户与状态条件。
--     也就是说：非用户 1 的孤儿已支付单会被"关闭但不退款"。若库里存在这类行，
--     执行前必须先把退款段扩到对应用户（或先查出这些人是谁再决定口径）。
--     执行前先清点（只读，可安全执行）：
--       SELECT p.biz_user_id, p.status, COUNT(*), SUM(p.amount) FROM `llmall-pay`.`pay_order` p
--        LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no
--        WHERE o.id IS NULL GROUP BY p.biz_user_id, p.status;
--   · 【注意】本脚本按 README 的定位属于"演示/清理数据"这一类（生产库只跑 00~41），
--     但它改的是余额，任何环境执行前都应先备份 pay_order 与 user 两张表。
--
-- ============================ 四、执行步骤 ============================
--   1. 备份（不可跳过，改的是钱）：
--        mysqldump -uroot -p llmall-pay pay_order > backup-52-pay-order.sql
--        mysqldump -uroot -p llmall-user user       > backup-52-user.sql
--   2. 先跑上面的清点 SQL，确认孤儿单只属于用户 1；有其它用户时先改口径（见"三、影响面"）。
--   3. 执行：mysql -uroot -p < 52-fix-orphan-pay-orders.sql
--   4. 看脚本末尾的自检输出，"剩余孤儿单"应为 0。
--   5. 人工核对退回金额与用户余额是否一致（金额单位是分）：
--        SELECT `id`, `balance` FROM `llmall-user`.`user` WHERE `id` = 1;
-- 幂等：第 3 步把孤儿单改成 status=2 后，"孤儿单"这个集合就空了 ——
--   重复执行时第 1 步算出的 @refund = 0，第 2 步加 0，第 3 步命中 0 行，是空操作。
--   【注意】幂等的前提是"第 3 步必须执行成功"：若只执行第 2 步（退款）而不执行第 3 步，
--   再跑一次会重复退款 —— 因此本脚本必须整份执行，不要分段手工跑。
--
-- ============================ 五、前置依赖 ============================
--   1) llmall-pay / llmall-trade / llmall-user 三个库与 pay_order、order、user 三张表已存在
--      （原商城脚本 + 31-order-alter.sql）。
--   2) 50-seed-local-life-data.sql（演示支付单来自它，且它写的 biz_order_no 是正确值）。
--   3) 顺序反了会怎样：
--      · 表不存在 → ERROR 1146；
--      · 在 50 号脚本之前执行 → 当时还没有孤儿单，脚本空跑（不算错，但也没有意义）；
--      · 应用侧仍在用 Number(order.id) 生成 bizOrderNo → 清理完还会继续产生新的孤儿单，
--        本脚本不是"修一次就永久解决"的方案。
--
-- ============================ 六、回滚方案 ============================
-- 【不可逆，只能靠备份还原】本脚本有两处无法用 SQL 反向抵消：
--   · 退款段直接 balance = balance + @refund，反向语句是"减回去"，但减之前无法判断
--     用户在这期间是否又有消费（余额是共享的一列），盲目相减会造成新的对不上账；
--   · 关闭段把 status 从 3/1 改成 2，原状态没有留痕（result_msg 只写了 ORPHAN 原因），
--     只能从备份里取回。
--   回滚（仅在确认无新增余额变动时可用，且必须先核对）：
--     UPDATE `llmall-user`.`user` SET balance = balance - <@refund 的值> WHERE id = 1;
--     UPDATE `llmall-pay`.`pay_order` p
--      LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no
--        SET p.status = 3, p.result_msg = NULL
--      WHERE o.id IS NULL AND p.biz_user_id = 1;      -- 仅还原被本脚本关闭的那些行
--   更可靠的做法：直接恢复备份
--     mysql -uroot -p llmall-pay  < backup-52-pay-order.sql
--     mysql -uroot -p llmall-user < backup-52-user.sql
--   注意恢复备份会把备份之后的其它余额变动一并覆盖，必须挑低峰窗口做。
--
-- ============================ 附、风险等级 ============================
-- 高 —— 直接改写资金：既改 user.balance（真实余额），又改 pay_order.status（支付状态），
--       且关闭段没有保存原状态，回滚语句本身带有"可能算错"的风险。
--       判据：涉及资金，且改错后不能自动恢复（只能从备份还原，或人工核对后手工相减），
--       属于"改写不可逆 + 涉及资金"两类高风险情形的叠加，故为高。
-- =====================================================================

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
