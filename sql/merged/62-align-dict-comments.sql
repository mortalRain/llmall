-- =====================================================================
-- 62-align-dict-comments.sql
-- 62 · 存量库字典注释对齐：把 7 个列的 COMMENT 改到与代码事实一致
--
-- ============================ 一、功能 ============================
-- 用 ALTER TABLE ... MODIFY COLUMN 修正 7 个列的注释（覆盖 8 项已登记的漂移问题），
-- 消除"按 DDL 注释写库会踩坑"的风险。
-- 【只改 COMMENT】列类型、可空性、默认值、索引与数据一律不动，本脚本不含任何 DML/DDL 之外的内容。
--
-- ============================ 二、背景 ============================
-- 依据：docs/字典与口径漂移清单-2026-10-15.md（9 处确认漂移，其中 1 处为 Java 注释、已修）
--      docs/DDL字典对齐工单-2026-10-15.md（逐条列出"当前注释 → 目标注释"与代码事实）
-- 典型危害：llmall-trade.order.status 的注释停留在课程原版的 6 值状态机（含 3 已发货 / 4 确认收货 /
-- 6 交易结束），而本项目实际只用 1 / 2 / 5 / 7 —— DBA 按注释写库会产生"永远查不出来"的状态。
--
-- ============================ 三、影响面 ============================
--   · 受影响：7 个列的 COMMENT 文本（item 1 处 / promotion 2 处 / pay 2 处 / trade 2 处）。
--   · 不受影响：列类型、可空性、默认值、索引、全部数据行 —— MODIFY 只替换列定义中的 COMMENT。
--   · 不受影响：全新初始化的库 —— 源文件已同步改为同一口径（见"五、前置依赖"），新部署可跳过本脚本。
--
-- ============================ 四、执行步骤 ============================
--   1) 执行前核对当前定义（不可跳过）：本脚本的列定义按 2026-10-15 的**实测值**编写
--      （information_schema.COLUMNS）；若你的库结构不同，请以 SHOW CREATE TABLE 为准调整：
--        SHOW CREATE TABLE `llmall-item`.`stock_flow`;
--   2) 执行：mysql -uroot -p < 62-align-dict-comments.sql
--   3) 复核（期望 7 行，且 COMMENT 与脚本内目标文本逐字一致）：
--        SELECT TABLE_SCHEMA,TABLE_NAME,COLUMN_NAME,COLUMN_COMMENT
--          FROM information_schema.COLUMNS
--         WHERE (TABLE_SCHEMA='llmall-item' AND TABLE_NAME='stock_flow' AND COLUMN_NAME='status')
--            OR (TABLE_SCHEMA='llmall-promotion' AND TABLE_NAME IN ('voucher','user_voucher') AND COLUMN_NAME='status')
--            OR (TABLE_SCHEMA='llmall-pay' AND TABLE_NAME='pay_order' AND COLUMN_NAME IN ('pay_type','status'))
--            OR (TABLE_SCHEMA='llmall-trade' AND TABLE_NAME IN ('order','message_log') AND COLUMN_NAME='status');
--
-- ============================ 五、前置依赖 ============================
--   · 需要下列建表脚本已执行：11-item-alter.sql（stock_flow）、20-promotion.sql（voucher/user_voucher）、
--     sql/llmall-pay.sql（pay_order）、sql/llmall-trade.sql（order）、02-shared-infra.sql（message_log）。
--   · 顺序反了会怎样：表未建就执行报 ERROR 1146 Unknown table，属正常（该库没有这张表）；
--     已建库上执行只对齐注释，无副作用。
--   · 源文件已同步（同一次变更的"双写"）：sql/llmall-pay.sql、sql/llmall-trade.sql、
--     sql/merged/11-item-alter.sql、sql/merged/20-promotion.sql、sql/merged/02-shared-infra.sql。
--
-- ============================ 六、回滚方案 ============================
--   可回滚：把 COMMENT 改回原文即可（原文逐条见 docs/DDL字典对齐工单-2026-10-15.md 第一节"当前注释"列），
--   写回方式同样是 MODIFY COLUMN、只替换 COMMENT 部分。
--   本脚本不含任何数据变更，因此不存在"数据回滚"问题。
--
-- ============================ 七、风险等级 ============================
-- 低 —— 只改 COMMENT。唯一需注意的是 MODIFY 必须写全列定义，本脚本已按实测值写全。
--
-- 幂等性：可重复执行，第二次结果与第一次完全相同。
-- =====================================================================

-- D-01 · llmall-item.stock_flow.status
-- 代码事实：扣减与回补都写 1；回补是新增一条正数 changeNum 的流水，没有任何写 2 的路径。
ALTER TABLE `llmall-item`.`stock_flow`
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 1
    COMMENT '1 有效（回补以新增正数流水表达，不存在 2 已回滚）';

-- D-02 · llmall-promotion.voucher.status
-- 代码事实：Voucher 只有 STATUS_ON=1 / STATUS_OFF=2，VoucherVO 同。
ALTER TABLE `llmall-promotion`.`voucher`
  MODIFY COLUMN `status` tinyint unsigned NOT NULL DEFAULT 1
    COMMENT '1 上架 2 下架';

-- D-03 + D-03b · llmall-promotion.user_voucher.status
-- 代码事实：Java 三处（UserVoucher / UserVoucherVO / api UserVoucherDTO）都是「1 未使用 2 已使用 3 已过期」；
-- 退款走"状态改回 1"，没有 4；值 2 的统一叫法是「已使用」而不是「已核销」。
ALTER TABLE `llmall-promotion`.`user_voucher`
  MODIFY COLUMN `status` tinyint unsigned NOT NULL DEFAULT 1
    COMMENT '1 未使用 2 已使用 3 已过期（退款为改回 1，无 4 已退款）';

-- D-04 · llmall-pay.pay_order.pay_type
-- 代码事实：PayType = JSAPI(1,网页支付JS) / MINI_APP(2,小程序) / APP(3,APP支付) / NATIVE(4,扫码支付) / BALANCE(5,余额支付)。
ALTER TABLE `llmall-pay`.`pay_order`
  MODIFY COLUMN `pay_type` tinyint NOT NULL DEFAULT 5
    COMMENT '支付类型：1 网页支付JS 2 小程序 3 APP支付 4 扫码支付 5 余额支付（与 PayType 枚举逐值一致）';

-- D-05 · llmall-pay.pay_order.status
-- 代码事实：PayStatus = 0 未提交 / 1 待支付 / 2 已关闭 / 3 支付成功；
-- 该字典本身没有"未知"态，未知线索在 result_code=UNCERTAIN（对账用）。
ALTER TABLE `llmall-pay`.`pay_order`
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 0
    COMMENT '支付状态：0 未提交 1 待支付 2 已关闭 3 支付成功（与 PayStatus 枚举逐值一致；无「未知」态，未知线索在 result_code=UNCERTAIN）';

-- D-06 · llmall-trade.order.status  【中风险：注释语义跨了两套状态机，建议单独复核】
-- 代码事实：Order.STATUS_* 实际只有 1 / 2 / 5 / 7；原注释里的 3 已发货、4 确认收货、6 交易结束本项目均不存在。
ALTER TABLE `llmall-trade`.`order`
  MODIFY COLUMN `status` tinyint(1) NULL DEFAULT NULL
    COMMENT '订单状态：1 未付款 2 已付款（待发货/待核销） 5 交易取消/订单关闭 7 已核销（与 Order.STATUS_* 逐值一致）';

-- D-07 · llmall-trade.message_log.status
-- 代码事实：MessageLogSupport 与 llmall-content / llmall-shop 两库都是「3 失败待重试」。
-- 【文本统一到 05/06 的写法】content 与 shop 两库的注释是「2 已完成(下游确认已处理)」——
-- 那一句说明了"什么才算完成"，信息更全，因此 trade 这一库向它对齐（而不是反过来）。
-- 若只统一到「2 已完成」，`dict-lint` 的 ④ 同列表述不一仍会命中（实测踩过）。
ALTER TABLE `llmall-trade`.`message_log`
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 0
    COMMENT '0 待发送 1 已投递 2 已完成(下游确认已处理) 3 失败待重试';
