-- 旧清理/直接退款入口已停用。操作说明：docs/test-data-safety.md。
-- 缺少完整业务关联，删除流水或覆盖余额会破坏支付及购券恢复协议。
-- 不保留可继续执行的写语句，即使 mysql --force 也不会执行旧清理。
SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'CLEANUP_DISABLED: retired legacy tool; see docs/test-data-safety.md';