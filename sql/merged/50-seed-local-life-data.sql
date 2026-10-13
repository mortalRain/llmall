-- =====================================================================
-- 50-seed-local-life-data.sql
-- 演示数据补齐：让"本地生活到店服务平台"看起来是一个真正在运营的系统
--
-- #####################################################################
-- # ⚠️ 仅演示库，勿在生产执行                                          #
-- # 本脚本"先按 id 区间 DELETE、再重插"来保证可重复执行，会删掉业务行：#
-- #   DELETE  llmall-item.item        id BETWEEN 900001 AND 999999      #
-- #   DELETE  llmall-content.blog     id BETWEEN 910001 AND 919999      #
-- #   DELETE  llmall-trade.order / order_detail   id ≈ 1900100000000000001 起 #
-- #   DELETE  llmall-pay.pay_order    id ≈ 1900200000000000001 起      #
-- # 另外会把 shop_id IS NULL 的实物商品批量置为下架（status=2）。       #
-- # 生产库只跑 00~41 的建库/建表/改造脚本；50/51/52/99 属演示数据。     #
-- #####################################################################
--
-- 背景（为什么要写这个脚本）：
--   合并两个项目时，商品域直接沿用了原商城工程的 8.8 万条京东实物商品（拉杆箱/手机），
--   shop_id 全为 NULL、type 全是 1（实物）；而门店套餐、探店评价、积分流水这些
--   "本地生活"侧的数据一条都没有 —— 结果就是：门店详情页的"套餐"永远空白、
--   评价列表永远空白、积分页永远空白。功能代码是通的，但演示起来像个空壳。
--
-- 本脚本做四件事（全部可重复执行）：
--   1. 给 14 家门店各生成 5 个到店套餐/团购券（type=2，挂到 shop_id 下）
--   2. 把 8.8 万条京东实物商品收敛为"每类精选 1 个、最多 45 个"，避免商城首页像杂货铺
--   3. 给每家门店生成 8 条探店笔记/评价（含评分），并据此重算门店热度分（与代码同一公式）
--   4. 为演示账号准备：余额、支付密码、积分、地址、3 笔订单（已付款/待付款/已核销）
--      + 对应支付单与积分流水
--
-- 执行方式见 sql/merged/README.md；执行完记得调一次
--   POST /search/view/rebuild  （重建搜索读模型）
-- =====================================================================

-- =====================================================================
-- 一、门店套餐（type=2，挂 shop_id）
--     id 规则：900000 + 门店id*10 + 序号，便于订单等其它数据引用
--     幂等：先按 id 区间删除本脚本生成的数据，再插入（比 ON DUPLICATE KEY 更直白，
--           也避免"带 JOIN 的 INSERT ... SELECT 不允许 ON DUPLICATE"这个 MySQL 限制）
-- =====================================================================
DELETE FROM `llmall-item`.`item` WHERE id BETWEEN 900001 AND 999999;

INSERT INTO `llmall-item`.`item`
  (id, shop_id, type, name, price, stock, image, category, brand, spec, sold, comment_count, isAD, status, create_time, update_time)
SELECT 900000 + s.id * 10 + p.n,
       s.id,
       2,
       CONCAT(s.name, '·',
              CASE s.type_id
                WHEN 1 THEN CASE p.n WHEN 1 THEN '单人套餐' WHEN 2 THEN '双人套餐' WHEN 3 THEN '四人套餐' WHEN 4 THEN '招牌尝鲜' ELSE '全场代金券' END
                WHEN 2 THEN CASE p.n WHEN 1 THEN '欢唱2小时(小包)' WHEN 2 THEN '黄金场欢唱3小时' WHEN 3 THEN '通宵欢唱套餐' WHEN 4 THEN '酒水小食套餐' ELSE '全场代金券' END
                WHEN 3 THEN CASE p.n WHEN 1 THEN '洗剪吹套餐' WHEN 2 THEN '烫染套餐' WHEN 3 THEN '护理SPA套餐' WHEN 4 THEN '总监造型' ELSE '全场代金券' END
                WHEN 4 THEN CASE p.n WHEN 1 THEN '单次体验券' WHEN 2 THEN '月卡' WHEN 3 THEN '季卡' WHEN 4 THEN '私教1对1' ELSE '全场代金券' END
                WHEN 5 THEN CASE p.n WHEN 1 THEN '60分钟足疗' WHEN 2 THEN '90分钟全身按摩' WHEN 3 THEN '双人包间套餐' WHEN 4 THEN '肩颈放松' ELSE '全场代金券' END
                WHEN 6 THEN CASE p.n WHEN 1 THEN '基础面部护理' WHEN 2 THEN '深层清洁套餐' WHEN 3 THEN '身体SPA套餐' WHEN 4 THEN '抗衰紧致套餐' ELSE '全场代金券' END
                WHEN 7 THEN CASE p.n WHEN 1 THEN '单次畅玩2小时' WHEN 2 THEN '亲子2小时(1大1小)' WHEN 3 THEN '全天畅玩票' WHEN 4 THEN '亲子月卡' ELSE '全场代金券' END
                WHEN 8 THEN CASE p.n WHEN 1 THEN '精酿啤酒2杯' WHEN 2 THEN '洋酒套餐' WHEN 3 THEN '卡座低消券' WHEN 4 THEN '小食拼盘' ELSE '全场代金券' END
                WHEN 9 THEN CASE p.n WHEN 1 THEN '4小时包场' WHEN 2 THEN '全天包场' WHEN 3 THEN '酒水畅饮套餐' WHEN 4 THEN '生日主题包场' ELSE '全场代金券' END
                WHEN 10 THEN CASE p.n WHEN 1 THEN '日式美甲基础款' WHEN 2 THEN '美甲+美睫套餐' WHEN 3 THEN '款式任选' WHEN 4 THEN '手足护理' ELSE '全场代金券' END
                ELSE CASE p.n WHEN 1 THEN '单人套餐' WHEN 2 THEN '双人套餐' WHEN 3 THEN '四人套餐' WHEN 4 THEN '招牌尝鲜' ELSE '全场代金券' END
              END),
       -- 定价按门店人均推导（注意：shop.avg_price 单位是「元」，商品 price 单位是「分」，故 ×100）
       -- 单人≈0.9 人均、双人≈1.7、四人≈3.2、尝鲜≈0.5、代金券≈2.0
       CASE p.n WHEN 1 THEN ROUND(s.avg_price * 0.9) * 100
                WHEN 2 THEN ROUND(s.avg_price * 1.7) * 100
                WHEN 3 THEN ROUND(s.avg_price * 3.2) * 100
                WHEN 4 THEN ROUND(s.avg_price * 0.5) * 100
                ELSE ROUND(s.avg_price * 2.0) * 100 END,
       100 + (s.id * 37 + p.n * 13) % 400                      AS stock,
       NULL                                                    AS image,   -- 前端用分类主题渐变封面兜底，避免外链图片失效
       CASE WHEN p.n = 5 THEN '代金券'
            WHEN s.type_id = 2 THEN 'KTV欢唱'
            WHEN s.type_id = 3 THEN '丽人美发'
            WHEN s.type_id = 4 THEN '健身运动'
            WHEN s.type_id = 5 THEN '按摩足疗'
            WHEN s.type_id = 6 THEN '美容SPA'
            WHEN s.type_id = 7 THEN '亲子游乐'
            WHEN s.type_id = 8 THEN '酒吧'
            WHEN s.type_id = 9 THEN '轰趴包场'
            WHEN s.type_id = 10 THEN '美甲美睫'
            ELSE '美食套餐' END,
       '同城生活',
       '到店核销 · 有效期30天 · 过期自动退',
       20 + (s.id * 91 + p.n * 47) % 2000                      AS sold,
       (20 + (s.id * 91 + p.n * 47) % 2000) DIV 10             AS comment_count,
       0, 1, NOW(), NOW()
FROM `llmall-shop`.`shop` s
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5) p;

-- =====================================================================
-- 二、实物商品收敛为"精选"
--     说明：不删除任何数据，只改 status（1 上架 / 2 下架）。
--     想恢复全部 8.8 万条：UPDATE `llmall-item`.`item` SET status = 1 WHERE shop_id IS NULL;
-- =====================================================================
UPDATE `llmall-item`.`item` SET status = 2 WHERE shop_id IS NULL AND status = 1;

UPDATE `llmall-item`.`item` i
JOIN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY category ORDER BY sold DESC, id ASC) rn
        FROM `llmall-item`.`item` WHERE shop_id IS NULL
    ) t WHERE t.rn <= 3
    ORDER BY id LIMIT 45
) x ON x.id = i.id
SET i.status = 1;

-- =====================================================================
-- 三、探店笔记 / 评价：每家门店 8 条（含 40~50 分评分，供热度分使用）
--     id 用 910000 + (门店id-1)*8 + 序号，保证可重复执行
-- =====================================================================
DELETE FROM `llmall-content`.`blog` WHERE id BETWEEN 910001 AND 919999;

INSERT INTO `llmall-content`.`blog`
  (id, shop_id, user_id, title, images, content, score, liked, comments, status, create_time, update_time)
SELECT 910000 + (s.id - 1) * 8 + n.n                           AS id,
       s.id,
       1 + ((s.id + n.n) % 4)                                   AS user_id,
       CONCAT(CASE n.n
                WHEN 1 THEN '环境超赞，会回购'
                WHEN 2 THEN '性价比之王'
                WHEN 3 THEN '周末打卡好去处'
                WHEN 4 THEN '服务态度一级棒'
                WHEN 5 THEN '朋友聚会首选'
                WHEN 6 THEN '出品稳定，老顾客了'
                WHEN 7 THEN '排队也值得'
                ELSE '带娃来的，很满意' END, '｜', s.name)       AS title,
       ''                                                        AS images,
       CONCAT('在', IFNULL(s.area, '市区'), '逛到这家「', s.name, '」，',
              CASE n.n
                WHEN 1 THEN '门面不大但里面很宽敞，灯光和音乐都很舒服，坐下来就不想走了。'
                WHEN 2 THEN '人均才几十块，分量却给得很足，两个人点一个双人套餐完全够吃。'
                WHEN 3 THEN '周末人挺多，建议提前线上取号，我们等了大概十分钟就进去了。'
                WHEN 4 THEN '店员全程都很热情，主动帮忙加水、介绍套餐，体验感很好。'
                WHEN 5 THEN '包间很干净，设备也新，一群人来玩正合适，下次还订这里。'
                WHEN 6 THEN '来过好几次了，出品一直很稳，闭眼点都不会踩雷。'
                WHEN 7 THEN '排队的时候可以先在附近逛逛，翻台速度比想象中快。'
                ELSE '带小孩来的，有专门的区域，家长可以安心吃饭，细节做得到位。' END,
              '地址在', IFNULL(s.address, '商圈内'), '，导航直接能到。')  AS content,
       40 + ((s.id * 7 + n.n * 3) % 11)                          AS score,     -- 4.0 ~ 5.0
       5 + ((s.id + n.n * 5) % 200)                              AS liked,
       (s.id + n.n) % 20                                         AS comments,
       1,
       DATE_SUB(NOW(), INTERVAL ((s.id * 3 + n.n * 5) % 45) DAY) AS create_time,
       NOW()
FROM `llmall-shop`.`shop` s
JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8) n;

-- 最近评价时间 = 热度时间衰减的锚点（原数据为 NULL，会导致衰减计算无从下手）
UPDATE `llmall-shop`.`shop` s
LEFT JOIN (SELECT shop_id, MAX(create_time) mx FROM `llmall-content`.`blog` GROUP BY shop_id) b
       ON b.shop_id = s.id
SET s.last_review_time = IFNULL(b.mx, NOW());

-- 重算热度分：与 ShopServiceImpl#recomputeHotScore 完全同一公式
--   base  = score/10*4 + log10(comments+1)*3 + log10(sold+1)*3
--   decay = 1 / (1 + Δ天/7)
UPDATE `llmall-shop`.`shop`
SET hot_score = ROUND((score / 10 * 4
                       + LOG10(comments + 1) * 3
                       + LOG10(sold + 1) * 3)
                      / (1 + DATEDIFF(NOW(), last_review_time) / 7), 2),
    hot_update_time = NOW();

-- =====================================================================
-- 四、演示账号（手机号 13900000777，短信登录自动注册出来的那个）
--     支付密码与其它演示账号一致（123456），余额 5000 元，便于演示"余额支付"
-- =====================================================================
-- 支付密码与其它演示账号一致（123456）——注意不能写成同表子查询，MySQL 会报 1093
SET @demoPw = (SELECT password FROM `llmall-user`.`user` WHERE id = 1);
UPDATE `llmall-user`.`user` SET password = @demoPw, balance = 500000 WHERE id = 19;

INSERT INTO `llmall-user`.`user_info` (user_id, city, introduce, fans, followee, credits, level, create_time, update_time)
SELECT 19, '杭州', '爱吃爱逛的同城生活用户', 12, 36, 500, 3, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `llmall-user`.`user_info` WHERE user_id = 19);

-- 收货地址（地址只能属于自己，这里直接按 user_id=19 写）
INSERT INTO `llmall-user`.`address` (id, user_id, province, city, town, mobile, street, contact, is_default, notes)
SELECT 9001, 19, '浙江省', '杭州市', '西湖区', '13900000777', '文三路 90 号东部软件园 3 号楼 502', '同城君', 1, '公司'
WHERE NOT EXISTS (SELECT 1 FROM `llmall-user`.`address` WHERE id = 9001);

INSERT INTO `llmall-user`.`address` (id, user_id, province, city, town, mobile, street, contact, is_default, notes)
SELECT 9002, 19, '浙江省', '杭州市', '拱墅区', '13900000777', '远洋乐堤港 B 座 1801', '同城君', 0, '家'
WHERE NOT EXISTS (SELECT 1 FROM `llmall-user`.`address` WHERE id = 9002);

-- =====================================================================
-- 五、订单演示数据：3 笔（已付款 / 待付款 / 已核销）+ 明细 + 支付单 + 积分流水
--     固定 id，便于重复执行：先删后插
-- =====================================================================
DELETE FROM `llmall-trade`.`order_detail` WHERE order_id BETWEEN 1900100000000000001 AND 1900100000000000009;
DELETE FROM `llmall-trade`.`order`        WHERE id       BETWEEN 1900100000000000001 AND 1900100000000000009;
DELETE FROM `llmall-pay`.`pay_order`      WHERE id       BETWEEN 1900200000000000001 AND 1900200000000000009;

-- 5.1 已付款（门店 5 的双人套餐）—— 用于演示"到店出示核销码"
INSERT INTO `llmall-trade`.`order`
  (id, order_type, shop_id, voucher_id, verify_code, total_fee, payment_type, user_id, status, create_time, pay_time, update_time)
SELECT 1900100000000000001, 2, i.shop_id, NULL, 'A7K2M9Q4', i.price, 3, 19, 2,
       NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY, NOW()
FROM `llmall-item`.`item` i WHERE i.id = 900052;

INSERT INTO `llmall-trade`.`order_detail` (order_id, item_id, num, name, spec, price, image, create_time, update_time)
SELECT 1900100000000000001, i.id, 1, i.name, i.spec, i.price, i.image, NOW() - INTERVAL 2 DAY, NOW()
FROM `llmall-item`.`item` i WHERE i.id = 900052;

-- 5.2 待付款（门店 3 的双人套餐 ×2）—— 用于演示"去支付 / 取消订单"
INSERT INTO `llmall-trade`.`order`
  (id, order_type, shop_id, voucher_id, verify_code, total_fee, payment_type, user_id, status, create_time, update_time)
SELECT 1900100000000000002, 2, i.shop_id, NULL, NULL, i.price * 2, 3, 19, 1,
       NOW() - INTERVAL 3 HOUR, NOW()
FROM `llmall-item`.`item` i WHERE i.id = 900032;

INSERT INTO `llmall-trade`.`order_detail` (order_id, item_id, num, name, spec, price, image, create_time, update_time)
SELECT 1900100000000000002, i.id, 2, i.name, i.spec, i.price, i.image, NOW() - INTERVAL 3 HOUR, NOW()
FROM `llmall-item`.`item` i WHERE i.id = 900032;

-- 5.3 已核销（门店 1 的单人套餐 ×3）—— 用于演示"历史订单 / 到店消费完成"
INSERT INTO `llmall-trade`.`order`
  (id, order_type, shop_id, voucher_id, verify_code, total_fee, payment_type, user_id, status, create_time, pay_time, verify_time, update_time)
SELECT 1900100000000000003, 2, i.shop_id, NULL, 'B3X8P1R6', i.price * 3, 3, 19, 7,
       NOW() - INTERVAL 9 DAY, NOW() - INTERVAL 9 DAY, NOW() - INTERVAL 7 DAY, NOW()
FROM `llmall-item`.`item` i WHERE i.id = 900011;

INSERT INTO `llmall-trade`.`order_detail` (order_id, item_id, num, name, spec, price, image, create_time, update_time)
SELECT 1900100000000000003, i.id, 3, i.name, i.spec, i.price, i.image, NOW() - INTERVAL 9 DAY, NOW()
FROM `llmall-item`.`item` i WHERE i.id = 900011;

-- 5.4 支付单（对应已付款与已核销两笔）
INSERT INTO `llmall-pay`.`pay_order`
  (id, biz_order_no, pay_order_no, biz_user_id, pay_channel_code, amount, pay_type, status,
   pay_success_time, pay_over_time, qr_code_url, create_time, update_time, is_delete)
SELECT 1900200000000000001, o.id, 1900210000000000001, 19, 'balance', o.total_fee, 3, 3,
       o.pay_time, DATE_ADD(o.create_time, INTERVAL 120 MINUTE), '', o.create_time, NOW(), 0
FROM `llmall-trade`.`order` o WHERE o.id = 1900100000000000001;

INSERT INTO `llmall-pay`.`pay_order`
  (id, biz_order_no, pay_order_no, biz_user_id, pay_channel_code, amount, pay_type, status,
   pay_success_time, pay_over_time, qr_code_url, create_time, update_time, is_delete)
SELECT 1900200000000000002, o.id, 1900210000000000002, 19, 'balance', o.total_fee, 3, 3,
       o.pay_time, DATE_ADD(o.create_time, INTERVAL 120 MINUTE), '', o.create_time, NOW(), 0
FROM `llmall-trade`.`order` o WHERE o.id = 1900100000000000003;

-- 5.5 积分流水（100 分 = 1 元 = 1 积分，与 PointsServiceImpl 的折算一致）
INSERT INTO `llmall-user`.`points_flow` (user_id, points, type, biz_type, biz_id, remark, create_time)
SELECT 19, o.total_fee DIV 100, 1, 'ORDER_PAID', CAST(o.id AS CHAR), '订单支付获得积分', o.pay_time
FROM `llmall-trade`.`order` o
WHERE o.id IN (1900100000000000001, 1900100000000000003)
  AND NOT EXISTS (SELECT 1 FROM `llmall-user`.`points_flow` f
                  WHERE f.biz_type = 'ORDER_PAID' AND f.biz_id = CAST(o.id AS CHAR) AND f.type = 1);

-- 5.6 积分余额与流水对齐（500 是初始值，加上两笔订单积分）
UPDATE `llmall-user`.`user_info` ui
SET ui.credits = 500 + IFNULL((SELECT SUM(f.points) FROM `llmall-user`.`points_flow` f WHERE f.user_id = 19), 0),
    ui.update_time = NOW()
WHERE ui.user_id = 19;

-- =====================================================================
-- 六、自检
-- =====================================================================
SELECT '门店套餐(type=2)' item_group, COUNT(*) cnt FROM `llmall-item`.`item` WHERE type = 2
UNION ALL SELECT '上架实物商品', COUNT(*) FROM `llmall-item`.`item` WHERE type = 1 AND status = 1
UNION ALL SELECT '探店评价', COUNT(*) FROM `llmall-content`.`blog`
UNION ALL SELECT '演示账号订单', COUNT(*) FROM `llmall-trade`.`order` WHERE user_id = 19
UNION ALL SELECT '演示账号积分流水', COUNT(*) FROM `llmall-user`.`points_flow` WHERE user_id = 19
UNION ALL SELECT '演示账号地址', COUNT(*) FROM `llmall-user`.`address` WHERE user_id = 19;
