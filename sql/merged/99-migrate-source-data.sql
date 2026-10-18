-- =====================================================================
-- 99-migrate-dianping-data.sql
-- 99 · 把点评（dianping_src）的门店数据迁移到 llmall-shop
--
-- 执行：mysql -uroot -p < 99-migrate-dianping-data.sql
--
-- ============================ 一、功能 ============================
-- 把外部库 dianping_src 的 tb_shop_type、tb_shop 按 id upsert 进
-- llmall-shop.shop_type 与 llmall-shop.shop，并为新导入的门店预热热度分 hot_score。
--
-- ============================ 二、背景 ============================
-- 门店域的建表脚本（10-shop.sql）只建表、不带数据，演示与联调需要真实的门店数据；
-- 点评库的 tb_shop 有现成的门店（含坐标、评分、销量、营业时间），
-- 因此本脚本负责"把点评门店搬进来"，作为演示数据之外的另一条数据来源。
-- 为避免重复导入造成主键冲突，本脚本用 INSERT ... ON DUPLICATE KEY UPDATE：
--   已存在的 id 就地更新字段，不存在的 id 才插入，因此可重复执行。
-- 【不变量】hot_score 只在 = 0 时被预热：重跑本脚本不会覆盖线上由评价事件算出的热度分。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-shop.shop_type（按 id upsert：name / icon / sort 会被源库的值覆盖）、
--     llmall-shop.shop（按 id upsert：name / type_id / images / area / address / x / y /
--     avg_price / sold / comments / score / open_hours 会被源库的值覆盖）。
--   · 受影响：hot_score = 0 的门店行（被重算为
--     ROUND((score/10)*4 + LOG10(comments+1)*3 + LOG10(sold+1)*3, 4)）与 hot_update_time。
--   · 不受影响：llmall-shop.shop 的 status / last_review_time ——
--     门店 upsert 的列清单里没有它们，新导入行走建表默认值（status 默认 1 营业中）。
--   · 不受影响：id 与 dianping_src 不重叠的门店行（演示门店 101~116 在源库里不存在时不动）。
--   · 不受影响：其它库 —— 内容域（blog/follow）与用户域（user/user_info）的迁移分别在
--     30-content.sql / 21-user-alter.sql 末尾的注释块里（默认不迁移，避免与商城账号冲突）。
--   · 【注意】id 与演示门店重叠时会发生覆盖：源库的 name / address / x / y / score 等会
--     盖掉现有值，而 50 / 51 生成的套餐（llmall-item.item.shop_id）、评价
--     （llmall-content.blog.shop_id）仍指向这些 id，不会跟着更新 ——
--     表现为"门店改名了，套餐标题还是旧店名"。执行前先看清 id 是否重叠：
--       SELECT COUNT(*) FROM `dianping_src`.`tb_shop` s
--        JOIN `llmall-shop`.`shop` t ON t.id = s.id;
--
-- ============================ 四、执行步骤 ============================
--   1. 建源库并导入点评数据（不可跳过：本脚本第一句就 SELECT 它，库不存在直接 ERROR 1146）：
--        mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS dianping_src DEFAULT CHARSET utf8mb4;"
--        mysql -uroot -p dianping_src < dianping-src.sql
--      【注意】dianping-src.sql 里的 tb_seckill_voucher 用了 '0000-00-00' 默认值，
--      MySQL 8 会报 Invalid default value，导入时请加 --force 跳过该表；本项目不用那张表。
--   2. 确认 10-shop.sql 已执行（不可跳过：shop / shop_type 两张表由它建），
--      并确认 50 / 51-seed 脚本已按 README 的顺序跑完（本脚本排在最后，见"五、前置依赖"）。
--   3. 执行：mysql -uroot -p < 99-migrate-dianping-data.sql
--   4. 核对（期望：门店数与源库一致、热度分为 0 的门店为 0 行）：
--        SELECT COUNT(*) FROM `llmall-shop`.`shop`;
--        SELECT COUNT(*) FROM `llmall-shop`.`shop` WHERE `hot_score` = 0;
--   5. 调接口让检索读到新门店：
--        POST /search/view/rebuild    —— 重建搜索读模型
--        POST /shops/cache/geo/rebuild —— 重建门店地理缓存
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE + UPDATE ... WHERE hot_score = 0，
--   可重复执行；第二遍不会产生新行，也不会覆盖已算出的热度分。
--
-- ============================ 五、前置依赖 ============================
--   1) 外部库 dianping_src（不在本仓库内，建表数据在 D:\dsh\legacy\dianping-src 项目里）。
--   2) 00-create-databases.sql + 10-shop.sql（建 llmall-shop 库与两张表）。
--   3) 应用版本：无需特定版本，但热度分要生效需要门店域服务已在跑（或靠 50 号脚本重算）。
--   4) 顺序反了会怎样：
--      · 10 未跑 → ERROR 1146 Table 'llmall-shop.shop' doesn't exist；
--      · dianping_src 未建 → ERROR 1146 Table 'dianping_src.tb_shop' doesn't exist；
--      · 本脚本排在 50 / 51 之前 → 50 会按 shop 表全量生成套餐与评价，
--        之后再导入的门店将没有套餐与评价（要按 README 的 50 → 51 → 50 → 51 再跑一轮补齐）。
--        因此 README 把本脚本放在最后一步（第 7 步，可选）。
--
-- ============================ 六、回滚方案 ============================
-- 本脚本没有反向脚本，按"能不能自动回到执行前"分别处理：
--   · llmall-shop.shop / shop_type 中被本脚本覆盖过的行：原值已不可知，
--     只能从备份还原 —— 【执行前必须备份】（不可跳过）：
--       mysqldump -uroot -p llmall-shop shop shop_type > backup-99-shop.sql
--     【不可逆】覆盖后再想回到"门店名/坐标/评分的旧值"，没有备份就找不回来。
--   · 本脚本新插入的行（源库有、目标库原本没有的 id）：可以按 id 精确删掉，但需要
--     先知道是哪些 id —— 执行前用下面的语句留底，删除时才有依据：
--       SELECT s.id FROM `dianping_src`.`tb_shop` s
--        LEFT JOIN `llmall-shop`.`shop` t ON t.id = s.id WHERE t.id IS NULL;
--     （留底后用 DELETE FROM `llmall-shop`.`shop` WHERE id IN (...); 清理）
--   · 热度分：被重算过的行可以用 50-seed-local-life-data.sql 的公式整体重算，属可逆。
--   恢复备份（注意会覆盖备份之后的新增门店）：
--     mysql -uroot -p llmall-shop < backup-99-shop.sql
--
-- ============================ 附、风险等级 ============================
-- 中 —— 会改写（upsert 覆盖）llmall-shop.shop / shop_type 的存量行字段，并重算部分热度分；
--       都是可重复执行的语句，但覆盖掉的旧值本身不可自动还原。
--       判据：改错后能靠备份还原、且不涉及资金与账号安全，属"改写存量数据但可备份还原"，
--       故为中而不是高；又因为确实会改存量行而不是纯新增，也不到低。
-- =====================================================================

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
