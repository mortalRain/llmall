-- =====================================================================
-- 51-seed-more-data.sql
-- 把"所有可能被查询到的表"都铺上演示数据（第二轮补数据）
--
-- #####################################################################
-- # ⚠️ 仅演示库，勿在生产执行                                          #
-- # 本脚本"先按 id 区间 DELETE、再重插"来保证可重复执行，会删掉业务行：#
-- #   DELETE  llmall-shop.shop         id BETWEEN 101 AND 199           #
-- #   DELETE  llmall-content.blog_comments  id BETWEEN 940001 AND 949999 #
-- #   DELETE  llmall-search.search_history  id BETWEEN 980001 AND 989999 #
-- #   DELETE  llmall-promotion.voucher      id BETWEEN 8001 AND 8099     #
-- #   DELETE  llmall-promotion.user_voucher id BETWEEN 8101 AND 8199     #
-- #   DELETE  llmall-promotion.seckill_voucher voucher_id 8001~8099      #
-- #   DELETE  llmall-user.user_sign    id BETWEEN 990001 AND 999999      #
-- # 生产库只跑 00~41 的建库/建表/改造脚本；50/51/52/99 属演示数据。     #
-- #####################################################################
--
-- 背景：
--   只给门店/套餐/订单打了数据是不够的 —— 评价评论、点赞、关注、Feed 收件箱、
--   搜索历史、热词、门店经营数据、事件消费记录、优惠券、签到、物流……全是空表，
--   前端任何一个入口点进去都是空白页。而且门店只有"美食"和"KTV"两类，
--   首页的分类 chip 点到"丽人·美发""亲子游乐"就是 0 家门店。
--
-- 本脚本（可重复执行）：
--   一、补齐 8 个缺失门店类型的门店（共 16 家）→ 门店覆盖全部 10 类
--   二、内容域：评价评论、点赞、关注、Feed 收件箱
--   三、搜索域：搜索历史、热词补充
--   四、门店域：近 7 天经营数据（pv/uv）
--   五、五个库的事件消费记录（对账/幂等演示）
--   六、促销域：代金券、用户券、秒杀券
--   七、用户域：签到日历、用户画像补全、积分流水补类型、购物车
--   八、交易域：物流单
--   注意：门店套餐与探店评价由 50-seed-local-life-data.sql 生成（覆盖全部门店），
--         本脚本跑完需重新执行一次 50 号脚本，再调用 /search/view/rebuild 与 /shops/cache/geo/rebuild。
-- =====================================================================

-- =====================================================================
-- 一、补齐 8 个缺失类型的门店（id 101~116）
--     杭州坐标区间：经度 120.14~120.17，纬度 30.28~30.33
-- =====================================================================
DELETE FROM `llmall-shop`.`shop` WHERE id BETWEEN 101 AND 199;

INSERT INTO `llmall-shop`.`shop`
  (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, hot_score, hot_update_time, last_review_time, open_hours, status, create_time, update_time)
VALUES
  (101, '丝域养发·造型（武林店）', 3, '', '武林商圈', '杭州市下城区武林路 118 号', 120.1631, 30.2794, 168, 3120, 1420, 47, 0, NOW(), NOW(), '10:00-21:30', 1, NOW(), NOW()),
  (102, '尚品国际美发沙龙', 3, '', '运河上街', '杭州市拱墅区上塘路 458 号 2 层', 120.1502, 30.3201, 236, 1860, 860, 45, 0, NOW(), NOW(), '10:30-22:00', 1, NOW(), NOW()),
  (103, '乐刻运动健身（城西银泰店）', 4, '', '城西银泰', '杭州市西湖区丰潭路 380 号 4 层', 120.1214, 30.2938, 199, 5420, 2310, 48, 0, NOW(), NOW(), '07:00-23:00', 1, NOW(), NOW()),
  (104, '超级猩猩健身（黄龙店）', 4, '', '黄龙商圈', '杭州市西湖区黄龙体育中心西大厅', 120.1365, 30.2712, 259, 3980, 1540, 46, 0, NOW(), NOW(), '06:30-22:30', 1, NOW(), NOW()),
  (105, '良子足道（凤起路店）', 5, '', '凤起路', '杭州市上城区凤起路 336 号 3 层', 120.1702, 30.2645, 168, 7210, 3320, 48, 0, NOW(), NOW(), '11:00-次日02:00', 1, NOW(), NOW()),
  (106, '富侨足浴养生（滨江店）', 5, '', '滨江星光大道', '杭州市滨江区江南大道 228 号', 120.1728, 30.2115, 138, 5640, 2480, 46, 0, NOW(), NOW(), '11:00-次日01:00', 1, NOW(), NOW()),
  (107, '克丽缇娜美容SPA（湖滨店）', 6, '', '湖滨商圈', '杭州市上城区延安路 258 号 5 层', 120.1683, 30.2528, 388, 2140, 980, 49, 0, NOW(), NOW(), '10:00-21:00', 1, NOW(), NOW()),
  (108, '自然美SPA会馆（未来科技城）', 6, '', '未来科技城', '杭州市余杭区文一西路 1218 号', 120.0245, 30.2795, 458, 1360, 620, 47, 0, NOW(), NOW(), '10:00-22:00', 1, NOW(), NOW()),
  (109, '奈尔宝家庭中心（大悦城店）', 7, '', '大悦城', '杭州市拱墅区莫干山路 813 号 3 层', 120.1471, 30.3041, 268, 4320, 1980, 48, 0, NOW(), NOW(), '09:30-21:00', 1, NOW(), NOW()),
  (110, 'meland club 儿童乐园', 7, '', '运河上街', '杭州市拱墅区上塘路 458 号 4 层', 120.1508, 30.3195, 298, 2860, 1240, 46, 0, NOW(), NOW(), '09:30-20:30', 1, NOW(), NOW()),
  (111, 'MAO Livehouse 杭州', 8, '', '运河上街', '杭州市拱墅区上塘路 458 号 B1', 120.1495, 30.3208, 158, 6140, 2760, 47, 0, NOW(), NOW(), '19:00-次日03:00', 1, NOW(), NOW()),
  (112, 'Helens 小酒馆（武林店）', 8, '', '武林商圈', '杭州市下城区体育场路 218 号 2 层', 120.1642, 30.2778, 68, 12480, 5320, 45, 0, NOW(), NOW(), '18:00-次日04:00', 1, NOW(), NOW()),
  (113, '独墅轰趴馆（滨江店）', 9, '', '滨江', '杭州市滨江区江陵路 2028 号 6 层', 120.1745, 30.2085, 228, 1860, 820, 46, 0, NOW(), NOW(), '10:00-次日02:00', 1, NOW(), NOW()),
  (114, '乐party 主题轰趴（城北店）', 9, '', '城北', '杭州市拱墅区湖州街 66 号 3 层', 120.1442, 30.3298, 258, 1240, 560, 45, 0, NOW(), NOW(), '10:00-次日02:00', 1, NOW(), NOW()),
  (115, '美人计美甲美睫（湖滨店）', 10, '', '湖滨商圈', '杭州市上城区平海路 124 号 2 层', 120.1691, 30.2542, 128, 3480, 1620, 48, 0, NOW(), NOW(), '10:00-21:30', 1, NOW(), NOW()),
  (116, 'Lily Nails 日式美甲', 10, '', '武林商圈', '杭州市下城区延安路 588 号 3 层', 120.1662, 30.2761, 158, 2640, 1180, 47, 0, NOW(), NOW(), '10:30-21:00', 1, NOW(), NOW());

-- =====================================================================
-- 二、内容域：评价评论 / 点赞 / 关注 / Feed 收件箱
-- =====================================================================

-- 2.1 评价评论：给前 60 条评价各加 2 条评论 + 1 条回复（共 180 行）
DELETE FROM `llmall-content`.`blog_comments` WHERE id BETWEEN 940001 AND 949999;

INSERT INTO `llmall-content`.`blog_comments` (id, user_id, blog_id, parent_id, answer_id, content, liked, status, create_time, update_time)
SELECT 940000 + (b.id - 910000) * 3 + n.n,
       1 + ((b.id + n.n) % 4),
       b.id,
       CASE WHEN n.n = 3 THEN 940000 + (b.id - 910000) * 3 + 1 ELSE 0 END,
       0,
       CASE n.n
         WHEN 1 THEN '同感，上次去也是这个体验，性价比确实高。'
         WHEN 2 THEN '请问周末需要提前预约吗？想带家人一起去。'
         ELSE '回复楼上：周末最好提前一天订，现场等位要 20 分钟左右。'
       END,
       (b.id + n.n) % 12,
       1,   -- status=1 已发布（0 是待审核，接口只返回已发布的评论）
       DATE_ADD(b.create_time, INTERVAL (n.n * 3 + 1) HOUR),
       NOW()
FROM `llmall-content`.`blog` b
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3) n
WHERE b.id BETWEEN 910001 AND 910060;

-- 2.2 点赞：blog_id 与 user_id 组合唯一，取偶数 blog + 前 4 个用户
INSERT IGNORE INTO `llmall-content`.`blog_like` (id, blog_id, user_id, create_time)
SELECT 950000 + (b.id - 910000) * 4 + u.n, b.id, u.n, DATE_ADD(b.create_time, INTERVAL u.n HOUR)
FROM `llmall-content`.`blog` b
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) u
WHERE b.id BETWEEN 910001 AND 910240 AND (b.id + u.n) % 3 <> 0;

-- 2.3 关注关系（user_id 关注 follow_user_id）
INSERT IGNORE INTO `llmall-content`.`follow` (id, user_id, follow_user_id, create_time) VALUES
  (960001, 1, 2, NOW() - INTERVAL 30 DAY),
  (960002, 1, 3, NOW() - INTERVAL 26 DAY),
  (960003, 1, 4, NOW() - INTERVAL 21 DAY),
  (960004, 2, 1, NOW() - INTERVAL 18 DAY),
  (960005, 2, 3, NOW() - INTERVAL 15 DAY),
  (960006, 3, 1, NOW() - INTERVAL 12 DAY),
  (960007, 19, 1, NOW() - INTERVAL 9 DAY),
  (960008, 19, 2, NOW() - INTERVAL 7 DAY),
  (960009, 19, 3, NOW() - INTERVAL 5 DAY),
  (960010, 19, 4, NOW() - INTERVAL 3 DAY),
  (960011, 4, 19, NOW() - INTERVAL 2 DAY),
  (960012, 3, 19, NOW() - INTERVAL 1 DAY);

-- 2.4 Feed 收件箱（写扩散：大 V 发笔记时推给粉丝，演示"推拉结合"）
INSERT IGNORE INTO `llmall-content`.`feed_inbox` (id, user_id, blog_id, author_id, create_time)
SELECT 970000 + b.id - 910000, 19, b.id, b.user_id, b.create_time
FROM `llmall-content`.`blog` b
WHERE b.id BETWEEN 910001 AND 910240 AND b.user_id IN (1, 2, 3) AND (b.id % 7) = 0;

-- =====================================================================
-- 三、搜索域：搜索历史 / 热词补充
-- =====================================================================
DELETE FROM `llmall-search`.`search_history` WHERE id BETWEEN 980001 AND 989999;

INSERT INTO `llmall-search`.`search_history` (id, user_id, keyword, create_time) VALUES
  (980001, 19, '火锅', NOW() - INTERVAL 15 MINUTE),
  (980002, 19, '双人套餐', NOW() - INTERVAL 2 HOUR),
  (980003, 19, 'KTV', NOW() - INTERVAL 1 DAY),
  (980004, 19, '按摩', NOW() - INTERVAL 2 DAY),
  (980005, 19, '亲子游乐', NOW() - INTERVAL 4 DAY),
  (980006, 1, '烤肉', NOW() - INTERVAL 30 MINUTE),
  (980007, 1, '美甲', NOW() - INTERVAL 6 HOUR),
  (980008, 2, '足疗', NOW() - INTERVAL 3 HOUR),
  (980009, 3, '轰趴馆', NOW() - INTERVAL 1 DAY),
  (980010, 4, '酒吧', NOW() - INTERVAL 2 DAY);

-- 热词统计表结构是 (word, search_count, stat_date)：按"每日词频"记录，便于做热词榜的时间趋势
INSERT INTO `llmall-search`.`search_hot_word` (word, search_count, stat_date, create_time, update_time)
SELECT w.word, w.cnt, CURDATE(), NOW(), NOW() FROM (
  SELECT '火锅' word, 128 cnt UNION ALL SELECT '烤肉', 96 UNION ALL SELECT 'KTV', 88
  UNION ALL SELECT '按摩', 76 UNION ALL SELECT '亲子', 64 UNION ALL SELECT '美甲', 58
  UNION ALL SELECT '足疗', 52 UNION ALL SELECT '轰趴', 46 UNION ALL SELECT '双人套餐', 41
  UNION ALL SELECT '代金券', 37 UNION ALL SELECT '美发', 33
) w
WHERE NOT EXISTS (SELECT 1 FROM `llmall-search`.`search_hot_word` e
                  WHERE e.word = w.word AND e.stat_date = CURDATE());

-- =====================================================================
-- 四、门店域：近 7 天经营数据（pv/uv，可用于"门店经营看板"叙事）
-- =====================================================================
INSERT IGNORE INTO `llmall-shop`.`shop_stat` (shop_id, stat_date, pv, uv, create_time, update_time)
SELECT s.id, DATE_SUB(CURDATE(), INTERVAL n.n DAY),
       300 + (s.id * 37 + n.n * 91) % 4200,
       120 + (s.id * 19 + n.n * 43) % 1800,
       NOW(), NOW()
FROM `llmall-shop`.`shop` s
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
            UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) n;

-- =====================================================================
-- 五、五个库的事件消费记录（幂等/对账演示：同一事件只被消费一次）
-- =====================================================================
INSERT IGNORE INTO `llmall-shop`.`consume_record` (biz_type, biz_id, consumer, create_time) VALUES
  ('ORDER_PAID', '1900100000000000001', 'llmall-shop-service', NOW() - INTERVAL 2 DAY),
  ('ORDER_PAID', '1900100000000000003', 'llmall-shop-service', NOW() - INTERVAL 9 DAY),
  ('REVIEW_CREATED', '910012', 'llmall-shop-service', NOW() - INTERVAL 1 DAY);

INSERT IGNORE INTO `llmall-trade`.`consume_record` (biz_type, biz_id, consumer, create_time) VALUES
  ('PAY_SUCCESS', '1900100000000000001', 'llmall-trade-service', NOW() - INTERVAL 2 DAY),
  ('PAY_SUCCESS', '1900100000000000003', 'llmall-trade-service', NOW() - INTERVAL 9 DAY),
  ('ORDER_CREATED', '1900100000000000002', 'llmall-cart-service', NOW() - INTERVAL 3 HOUR);

INSERT IGNORE INTO `llmall-user`.`consume_record` (biz_type, biz_id, consumer, create_time) VALUES
  ('ORDER_PAID', '1900100000000000001', 'llmall-user-service', NOW() - INTERVAL 2 DAY),
  ('ORDER_PAID', '1900100000000000003', 'llmall-user-service', NOW() - INTERVAL 9 DAY),
  ('REVIEW_CREATED', '910012', 'llmall-user-service', NOW() - INTERVAL 1 DAY);

INSERT IGNORE INTO `llmall-search`.`consume_record` (biz_type, biz_id, consumer, create_time) VALUES
  ('SHOP_HOT_CHANGED', '1', 'llmall-search-service', NOW() - INTERVAL 1 DAY),
  ('SHOP_HOT_CHANGED', '5', 'llmall-search-service', NOW() - INTERVAL 6 HOUR),
  ('SHOP_HOT_CHANGED', '10', 'llmall-search-service', NOW() - INTERVAL 2 HOUR);

INSERT IGNORE INTO `llmall-promotion`.`consume_record` (biz_type, biz_id, consumer, create_time) VALUES
  ('ORDER_PAID', '1900100000000000001', 'llmall-promotion-service', NOW() - INTERVAL 2 DAY),
  ('ORDER_CANCELED', '1900100000000000002', 'llmall-promotion-service', NOW() - INTERVAL 1 HOUR);

-- =====================================================================
-- 六、促销域：代金券 / 用户已领券 / 秒杀券
-- =====================================================================
DELETE FROM `llmall-promotion`.`voucher` WHERE id BETWEEN 8001 AND 8099;
INSERT INTO `llmall-promotion`.`voucher` (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status, create_time, update_time) VALUES
  (8001, 1,  '103茶餐厅 100 元代金券', '全场通用·可叠加会员折扣', '{"有效天数":30,"限购":2,"使用门槛":"满100可用"}', 8900,  10000, 0, 1, NOW(), NOW()),
  (8002, 1,  '103茶餐厅 9 折券',       '仅限堂食',                  '{"有效天数":15,"限购":1,"使用门槛":"无门槛"}',   0,     9000,  1, 1, NOW(), NOW()),
  (8003, 5,  '海底捞 200 元代金券',    '工作日午市可用',            '{"有效天数":30,"限购":1,"使用门槛":"满200可用"}', 17800, 20000, 0, 1, NOW(), NOW()),
  (8004, 10, '开乐迪KTV 3 小时欢唱券', '黄金场·小包',               '{"有效天数":30,"限购":3,"使用门槛":"需提前预约"}', 9800,  12800, 0, 1, NOW(), NOW()),
  (8005, 11, 'INLOVE KTV 通宵券',      '周五周六不可用',            '{"有效天数":20,"限购":1,"使用门槛":"需提前预约"}', 16800, 24000, 0, 1, NOW(), NOW()),
  (8006, 103,'乐刻健身 单次体验券',     '含团课一次',                '{"有效天数":7,"限购":1,"使用门槛":"新用户专享"}',  1900,  9900,  0, 1, NOW(), NOW()),
  (8007, 105,'良子足道 60 分钟券',      '经典足疗',                  '{"有效天数":30,"限购":2,"使用门槛":"无门槛"}',     12800, 16800, 0, 1, NOW(), NOW()),
  (8008, 109,'奈尔宝 亲子 2 小时券',    '一大一小',                  '{"有效天数":30,"限购":2,"使用门槛":"需预约场次"}', 18800, 26800, 0, 1, NOW(), NOW()),
  (8009, 112,'Helens 小酒馆 100 元券',  '全场通用',                  '{"有效天数":15,"限购":5,"使用门槛":"满100可用"}',  8500,  10000, 0, 1, NOW(), NOW()),
  (8010, 115,'美人计美甲 日式美甲券',   '含卸甲',                    '{"有效天数":30,"限购":3,"使用门槛":"无门槛"}',     9800,  12800, 0, 1, NOW(), NOW());

DELETE FROM `llmall-promotion`.`user_voucher` WHERE id BETWEEN 8101 AND 8199;
INSERT INTO `llmall-promotion`.`user_voucher` (id, user_id, voucher_id, shop_id, order_id, status, receive_time, use_time, expire_time, update_time) VALUES
  (8101, 19, 8003, 5,  NULL, 1, NOW() - INTERVAL 3 DAY, NULL, NOW() + INTERVAL 27 DAY, NOW()),
  (8102, 19, 8004, 10, NULL, 1, NOW() - INTERVAL 2 DAY, NULL, NOW() + INTERVAL 28 DAY, NOW()),
  (8103, 19, 8001, 1,  1900100000000000003, 2, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 9 DAY, NOW() + INTERVAL 20 DAY, NOW()),
  (8104, 1,  8005, 11, NULL, 1, NOW() - INTERVAL 1 DAY, NULL, NOW() + INTERVAL 19 DAY, NOW());

DELETE FROM `llmall-promotion`.`seckill_voucher` WHERE voucher_id BETWEEN 8001 AND 8099;
INSERT INTO `llmall-promotion`.`seckill_voucher` (voucher_id, stock, begin_time, end_time, create_time, update_time) VALUES
  (8001, 200, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 2 DAY, NOW(), NOW()),
  (8004, 120, NOW() - INTERVAL 6 HOUR, NOW() + INTERVAL 1 DAY, NOW(), NOW());

-- =====================================================================
-- 七、用户域：画像补全 / 签到 / 积分 / 购物车
-- =====================================================================
INSERT IGNORE INTO `llmall-user`.`user_info` (user_id, city, introduce, fans, followee, gender, birthday, credits, level, create_time, update_time) VALUES
  (1,  '杭州', '爱吃爱逛，探店 5 年老饕', 128, 36, 1, '1995-06-18', 1280, 5, NOW(), NOW()),
  (2,  '杭州', 'KTV 麦霸，周末必开嗓',    86,  52, 2, '1997-03-02', 960,  4, NOW(), NOW()),
  (3,  '杭州', '健身+按摩，养生派',        54,  41, 1, '1993-11-25', 740,  3, NOW(), NOW()),
  (4,  '杭州', '带娃探店专业户',            72,  63, 2, '1990-08-09', 1520, 5, NOW(), NOW());

-- 【修复记录 · 2026-10 审计】下面的 DELETE 区间原来写成 990001 AND 999999，
--   而 INSERT 生成的 id 是 990000 + n（n = 0..5，最小 990000）—— 区间左端少算了 1，
--   于是 990000 这一行永远删不掉，第二次执行报
--       ERROR 1062 (23000): Duplicate entry '990000' for key 'user_sign.PRIMARY'
--   这个 bug 只在"第二遍执行"时才暴露，本次"连续跑两遍"的验收把它抓了出来。
DELETE FROM `llmall-user`.`user_sign` WHERE id BETWEEN 990000 AND 999999;
INSERT INTO `llmall-user`.`user_sign` (id, user_id, year, month, date, is_backup, create_time)
SELECT 990000 + n.n, 19, YEAR(DATE_SUB(CURDATE(), INTERVAL n.n DAY)), MONTH(DATE_SUB(CURDATE(), INTERVAL n.n DAY)),
       DATE_SUB(CURDATE(), INTERVAL n.n DAY), CASE WHEN n.n = 3 THEN 1 ELSE 0 END, NOW()
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5) n;

INSERT INTO `llmall-user`.`points_flow` (user_id, points, type, biz_type, biz_id, remark, create_time)
SELECT 19, 20, 2, 'REVIEW_CREATED', CAST(b.id AS CHAR), '发布评价获得积分', b.create_time
FROM `llmall-content`.`blog` b
WHERE b.user_id = 19 AND b.id BETWEEN 910001 AND 910240
  AND NOT EXISTS (SELECT 1 FROM `llmall-user`.`points_flow` f WHERE f.biz_type = 'REVIEW_CREATED' AND f.biz_id = CAST(b.id AS CHAR));

INSERT INTO `llmall-user`.`points_flow` (user_id, points, type, biz_type, biz_id, remark, create_time)
SELECT 19, 5, 3, 'SIGN_IN', CAST(DATE_SUB(CURDATE(), INTERVAL n.n DAY) AS CHAR), '每日签到', DATE_SUB(NOW(), INTERVAL n.n DAY)
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) n
WHERE NOT EXISTS (SELECT 1 FROM `llmall-user`.`points_flow` f WHERE f.biz_type = 'SIGN_IN' AND f.biz_id = CAST(DATE_SUB(CURDATE(), INTERVAL n.n DAY) AS CHAR));

INSERT INTO `llmall-user`.`address` (id, user_id, province, city, town, mobile, street, contact, is_default, notes)
SELECT 9003, 19, '浙江省', '杭州市', '滨江区', '13900000777', '江南大道 228 号星光大道 3 幢 1201', '同城君', 0, '公司分部'
WHERE NOT EXISTS (SELECT 1 FROM `llmall-user`.`address` WHERE id = 9003);

INSERT IGNORE INTO `llmall-cart`.`cart` (id, user_id, item_id, num, name, spec, price, image)
SELECT 90001, 19, 900052, 1, i.name, i.spec, i.price, i.image FROM `llmall-item`.`item` i WHERE i.id = 900052;

INSERT IGNORE INTO `llmall-cart`.`cart` (id, user_id, item_id, num, name, spec, price, image)
SELECT 90002, 19, 900103, 2, i.name, i.spec, i.price, i.image FROM `llmall-item`.`item` i WHERE i.id = 900103;

-- =====================================================================
-- 八、交易域：物流单（实物订单）
-- =====================================================================
INSERT IGNORE INTO `llmall-trade`.`order_logistics`
  (order_id, logistics_number, logistics_company, contact, mobile, province, city, town, street, create_time, update_time)
SELECT o.id, CONCAT('SF', LPAD(o.id % 100000000, 12, '0')), '顺丰速运', 'Jack', '13900112224',
       '浙江省', '杭州市', '西湖区', '文三路 90 号 3 号楼 502', o.create_time, NOW()
FROM `llmall-trade`.`order` o
WHERE o.order_type = 1 AND o.status IN (2, 3, 4)
  AND NOT EXISTS (SELECT 1 FROM `llmall-trade`.`order_logistics` l WHERE l.order_id = o.id);

-- =====================================================================
-- 九、自检
-- =====================================================================
SELECT '门店总数' t, COUNT(*) c FROM `llmall-shop`.`shop`
UNION ALL SELECT '门店类型覆盖数', COUNT(DISTINCT type_id) FROM `llmall-shop`.`shop`
UNION ALL SELECT '门店套餐', COUNT(*) FROM `llmall-item`.`item` WHERE type = 2
UNION ALL SELECT '探店评价', COUNT(*) FROM `llmall-content`.`blog`
UNION ALL SELECT '评价评论', COUNT(*) FROM `llmall-content`.`blog_comments`
UNION ALL SELECT '点赞', COUNT(*) FROM `llmall-content`.`blog_like`
UNION ALL SELECT '关注关系', COUNT(*) FROM `llmall-content`.`follow`
UNION ALL SELECT 'Feed 收件箱', COUNT(*) FROM `llmall-content`.`feed_inbox`
UNION ALL SELECT '搜索历史', COUNT(*) FROM `llmall-search`.`search_history`
UNION ALL SELECT '热词', COUNT(*) FROM `llmall-search`.`search_hot_word`
UNION ALL SELECT '门店经营数据', COUNT(*) FROM `llmall-shop`.`shop_stat`
UNION ALL SELECT '优惠券', COUNT(*) FROM `llmall-promotion`.`voucher`
UNION ALL SELECT '用户券', COUNT(*) FROM `llmall-promotion`.`user_voucher`
UNION ALL SELECT '签到记录', COUNT(*) FROM `llmall-user`.`user_sign`
UNION ALL SELECT '购物车条目', COUNT(*) FROM `llmall-cart`.`cart`
UNION ALL SELECT '物流单', COUNT(*) FROM `llmall-trade`.`order_logistics`;
