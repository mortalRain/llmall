-- =====================================================================
-- 52a-orphan-pay-orders-audit.sql
-- 孤儿支付单【只读清点】—— 在决定怎么改 52-fix-orphan-pay-orders.sql 之前先跑本脚本
--
-- ============================ 一、功能 ============================
-- 只读统计"支付单指向了不存在的订单"（孤儿单）的规模与金额，不做任何修改。
-- 目的是把"52 号脚本的口径要不要改、怎么改"从猜测变成事实。
--
-- ============================ 二、背景 ============================
-- 52 号脚本的第 1 步（退款）与第 3 步（关闭）口径不一致：
--     退款段：WHERE o.id IS NULL AND p.status = 3 AND p.biz_user_id = 1
--     关闭段：WHERE o.id IS NULL                 ← 不带用户、也不带状态
-- 因此若库里存在「非 biz_user_id = 1 的已支付孤儿单」，执行 52 会把这些单关闭却不退款
-- （用户的钱没了、单也关了）。本脚本的第 3 个查询就是给这个数量与金额下结论。
--
-- ============================ 三、影响面 ============================
-- 全部为 SELECT：不含任何 INSERT / UPDATE / DELETE / DDL，可直接在生产执行。
--
-- ============================ 四、执行步骤 ============================
--     mysql -uroot -p < 52a-orphan-pay-orders-audit.sql
--
-- ============================ 五、怎么读结果 ============================
--   查询 3 的 non_user1_paid_orphans = 0  → 关闭集 ⊆ 退款集，52 号脚本按现状执行是安全的
--   查询 3 的 non_user1_paid_orphans > 0  → 先不要执行 52：
--        需要先把退款段改成全量口径（去掉 biz_user_id = 1 限制），或把关闭段的 WHERE
--        收敛成与退款段一致（只关"已支付且属于用户 1"的单），二者选一。
--   查询 2 给出逐笔明细，供人工核对与后续补退。
--
-- ============================ 六、风险等级 ============================
-- 无 —— 纯只读，不修改任何数据。
-- =====================================================================

-- 1) 规模与金额：按「是否属于用户 1」× 「支付状态」分组
SELECT
  CASE WHEN p.biz_user_id = 1 THEN 'user1' ELSE 'other_users' END          AS user_scope,
  CASE WHEN p.status = 3 THEN 'paid(3)' ELSE CONCAT('status=', p.status) END AS pay_state,
  COUNT(*)                                                                 AS orders,
  IFNULL(SUM(p.amount), 0)                                                 AS amount_cents
FROM `llmall-pay`.`pay_order` p
LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no
WHERE o.id IS NULL
GROUP BY user_scope, pay_state
ORDER BY user_scope, pay_state;

-- 2) 逐笔明细（生产上如量大可自行加 LIMIT，或按 create_time 过滤）
SELECT p.id, p.biz_order_no, p.biz_user_id, p.status, p.amount, p.result_msg, p.create_time
FROM `llmall-pay`.`pay_order` p
LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no
WHERE o.id IS NULL
ORDER BY p.biz_user_id, p.id;

-- 3) 一句话结论（核心判据：非 user1 的已支付孤儿单是否真的存在）
SELECT
  SUM(CASE WHEN p.status = 3 AND p.biz_user_id <> 1 THEN 1 ELSE 0 END)                      AS non_user1_paid_orphans,
  IFNULL(SUM(CASE WHEN p.status = 3 AND p.biz_user_id <> 1 THEN p.amount ELSE 0 END), 0)    AS non_user1_paid_cents,
  CASE WHEN SUM(CASE WHEN p.status = 3 AND p.biz_user_id <> 1 THEN 1 ELSE 0 END) = 0
       THEN '安全：52 号脚本按现状执行不会漏退款'
       ELSE '危险：存在非 user1 的已支付孤儿单，执行 52 会关单不退款，请先统一口径'
  END                                                                                       AS verdict
FROM `llmall-pay`.`pay_order` p
LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no
WHERE o.id IS NULL;
