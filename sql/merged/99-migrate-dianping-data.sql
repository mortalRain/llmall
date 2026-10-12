-- ============================================================
-- 99 · 把点评（dianping_src）的门店数据迁移到 llmall-shop
-- 前提：本机已存在 dianping_src 库并导入过 dianping-src.sql
--       （dianping-src.sql 里的 tb_seckill_voucher 用了 '0000-00-00' 默认值，MySQL 8 会报
--        Invalid default value，导入时请加 --force 跳过该表；本项目不用那张表）
-- 幂等：先按 id 删除再插入（可重复执行）
-- ============================================================

-- 门店类型
INSERT INTO `llmall-shop`.`shop_type` (id, name, icon, sort, create_time, update_time)
SELECT id, name, icon, sort, create_time, update_time
FROM `dianping_src`.`tb_shop_type`
ON DUPLICATE KEY UPDATE name = VALUES(name), icon = VALUES(icon), sort = VALUES(sort);

-- 门店（新增的 hot_score / last_review_time / status 走默认值）
INSERT INTO `llmall-shop`.`shop`
    (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours, create_time, update_time)
SELECT id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours, create_time, update_time
FROM `dianping_src`.`tb_shop`
ON DUPLICATE KEY UPDATE
    name = VALUES(name), type_id = VALUES(type_id), images = VALUES(images), area = VALUES(area),
    address = VALUES(address), x = VALUES(x), y = VALUES(y), avg_price = VALUES(avg_price),
    sold = VALUES(sold), comments = VALUES(comments), score = VALUES(score), open_hours = VALUES(open_hours);

-- 预热热度分：base = 评分×4 + log10(评价数+1)×3 + log10(销量+1)×3（时间衰减因子因缺少评价时间暂按 1 计）
UPDATE `llmall-shop`.`shop`
SET hot_score = ROUND((score / 10) * 4 + LOG10(comments + 1) * 3 + LOG10(sold + 1) * 3, 4),
    hot_update_time = NOW()
WHERE hot_score = 0;

-- 说明：内容域（blog/follow）与用户域（user/user_info）的数据迁移见
--      30-content.sql 与 21-user-alter.sql 末尾的注释（默认不迁移，避免与商城账号冲突）。
