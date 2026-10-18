-- =====================================================================
-- 55-seed-core-shops.sql
-- 补齐 14 家核心门店（id 1~14）与它们所属的两个门店类型（id 1 / 2）
--
-- ============================ 一、为什么需要它 ============================
-- 演示库的 30 家门店分成两批：
--   · id 1~14  —— 9 家餐饮 + 5 家 KTV，原始数据来自点评库迁移（99-migrate-source-data.sql）
--   · id 101~116 —— 51-seed-more-data.sql 按"8 个缺失类型各 2 家"生成
-- 而冒烟脚本 scripts/MallE2E.java 的断言是**按门店 id 写死**的：
--   GET /shops/1 的人均必须能读到、GET /items/900013 必须存在
--   （900013 = 900000 + 门店 1 × 10 + 序号 3，由 50 号脚本按门店生成）。
-- 也就是说：id 1~14 这批门店一旦缺席，全新库上冒烟连第一条商品断言都过不去，
-- 而"30 家门店 × 5 档 = 150 个到店套餐"这条聚合断言也只会得到 80。
--
-- 99 号迁移脚本依赖**仓库外**的 dianping_src 库，从零复现时不可用（demo.ps1 默认跳过它）。
-- 于是把这两批里"缺的那一批"直接固化成可重放的种子数据：全新库跑完本脚本，
-- 门店集合就与演示库一致，不再依赖任何外部库。
--
-- ============================ 二、幂等性 ============================
-- 两条语句都是 INSERT IGNORE：主键已存在的行整行跳过。
-- 对已经在用的演示库（这 14 行早就存在）命中 0 行、不覆盖任何线上演示数据 ——
-- 这一点刻意不用 ON DUPLICATE KEY UPDATE：种子脚本不该把长期演示库里的
-- 售量 / 评价数 / 热度这些"跑出来"的统计值打回去。
--
-- ============================ 三、执行位置 ============================
-- 依赖 10-shop.sql 建出的 shop / shop_type 表；必须在 50-seed-local-life-data.sql **之前** ——
-- 50 号脚本按 shop 表全量生成套餐，门店少了它就只能生成一部分。
-- demo.ps1 的 SQL 清单已按此顺序登记本脚本。
--
-- ============================ 四、数据来源 ============================
-- 14 行门店与 2 个门店类型取自演示库（192.168.189.128）的当前值，
-- 字段口径与 10-shop.sql 的列定义一致（avg_price 单位=元，热度为 decimal(12,4)）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 两个门店类型：美食（1）与 KTV（2）。
--    51 号脚本只补 3~10 这 8 个"缺失类型"，1 / 2 属于核心门店那批，故在这里补齐。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `llmall-shop`.`shop_type` (`id`, `name`, `sort`, `icon`) VALUES
  (1, '美食', 1, '/types/ms.png'),
  (2, 'KTV', 2, '/types/KTV.png');

-- ---------------------------------------------------------------------
-- 2) 14 家核心门店（id 1~14）。
--    create_time / update_time 保留原始时间：它们参与列表排序与"新鲜度"展示。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `llmall-shop`.`shop`
  (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score,
   hot_score, hot_update_time, last_review_time, open_hours, status, create_time, update_time)
VALUES
  (1, '103茶餐厅', 1, 'https://qcloud.dpfile.com/pc/jiclIsCKmOI2arxKN1Uf0Hx3PucIJH8q0QSz-Z8llzcN56-_QiKuOvyio1OOxsRtFoXqu0G3iT2T27qat3WhLVEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vfCF2ubeXzk49OsGrXt_KYDCngOyCwZK-s3fqawWswzk.jpg,https://qcloud.dpfile.com/pc/IOf6VX3qaBgFXFVgp75w-KKJmWZjFc8GXDU8g9bQC6YGCpAmG00QbfT4vCCBj7njuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '大关', '金华路锦昌文华苑29号', 120.149192, 30.316078, 80, 4223, 3043, 37, 23.6704, '2026-10-13 19:10:21', '2026-10-13 19:10:20', '10:00-22:00', 1, '2021-12-22 18:10:39', '2026-10-13 19:10:21'),
  (2, '蔡馬洪涛烤肉·老北京铜锅涮羊肉', 1, 'https://p0.meituan.net/bbia/c1870d570e73accbc9fee90b48faca41195272.jpg,http://p0.meituan.net/mogu/397e40c28fc87715b3d5435710a9f88d706914.jpg,https://qcloud.dpfile.com/pc/MZTdRDqCZdbPDUO0Hk6lZENRKzpKRF7kavrkEI99OxqBZTzPfIxa5E33gBfGouhFuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '拱宸桥/上塘', '上塘路1035号（中国工商银行旁）', 120.151505, 30.333422, 85, 2160, 1460, 46, 33.1600, '2026-10-13 12:24:02', '2026-10-12 12:24:02', '11:30-03:00', 1, '2021-12-22 19:00:13', '2026-10-13 12:24:02'),
  (3, '新白鹿餐厅(运河上街店)', 1, 'https://p0.meituan.net/biztone/694233_1619500156517.jpeg,https://img.meituan.net/msmerchant/876ca8983f7395556eda9ceb064e6bc51840883.png,https://img.meituan.net/msmerchant/86a76ed53c28eff709a36099aefe28b51554088.png', '运河上街', '台州路2号运河上街购物中心F5', 120.151954, 30.32497, 61, 12035, 8045, 47, 27.2100, '2026-10-13 12:24:02', '2026-10-09 12:24:02', '10:30-21:00', 1, '2021-12-22 19:10:05', '2026-10-13 12:24:02'),
  (4, 'Mamala(杭州远洋乐堤港店)', 1, 'https://img.meituan.net/msmerchant/232f8fdf09050838bd33fb24e79f30f9606056.jpg,https://qcloud.dpfile.com/pc/rDe48Xe15nQOHCcEEkmKUp5wEKWbimt-HDeqYRWsYJseXNncvMiXbuED7x1tXqN4uzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '拱宸桥/上塘', '丽水路66号远洋乐堤港商城2期1层B115号', 120.146659, 30.312742, 290, 13519, 9529, 49, 34.1700, '2026-10-13 12:24:02', '2026-10-11 12:24:02', '11:00-22:00', 1, '2021-12-22 19:17:15', '2026-10-13 12:24:02'),
  (5, '海底捞火锅(水晶城购物中心店）', 1, 'https://img.meituan.net/msmerchant/054b5de0ba0b50c18a620cc37482129a45739.jpg,https://img.meituan.net/msmerchant/59b7eff9b60908d52bd4aea9ff356e6d145920.jpg,https://qcloud.dpfile.com/pc/Qe2PTEuvtJ5skpUXKKoW9OQ20qc7nIpHYEqJGBStJx0mpoyeBPQOJE4vOdYZwm9AuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '大关', '上塘路458号水晶城购物中心F6', 120.15778, 30.310633, 104, 4125, 2764, 49, 40.7700, '2026-10-13 12:24:02', '2026-10-13 12:24:02', '10:00-07:00', 1, '2021-12-22 19:20:58', '2026-10-13 12:24:02'),
  (6, '幸福里老北京涮锅（丝联店）', 1, 'https://img.meituan.net/msmerchant/e71a2d0d693b3033c15522c43e03f09198239.jpg,https://img.meituan.net/msmerchant/9f8a966d60ffba00daf35458522273ca658239.jpg,https://img.meituan.net/msmerchant/ef9ca5ef6c05d381946fe4a9aa7d9808554502.jpg', '拱宸桥/上塘', '金华南路189号丝联166号', 120.148603, 30.318618, 130, 9531, 7324, 46, 29.3500, '2026-10-13 12:24:02', '2026-10-10 12:24:02', '11:00-13:50,17:00-20:50', 1, '2021-12-22 19:24:53', '2026-10-13 12:24:02'),
  (7, '炉鱼(拱墅万达广场店)', 1, 'https://img.meituan.net/msmerchant/909434939a49b36f340523232924402166854.jpg,https://img.meituan.net/msmerchant/32fd2425f12e27db0160e837461c10303700032.jpg,https://img.meituan.net/msmerchant/f7022258ccb8dabef62a0514d3129562871160.jpg', '北部新城', '杭行路666号万达商业中心4幢2单元409室(铺位号4005)', 120.124691, 30.336819, 85, 2631, 1320, 47, 33.6200, '2026-10-13 12:24:02', '2026-10-12 12:24:02', '00:00-24:00', 1, '2021-12-22 19:40:52', '2026-10-13 12:24:02'),
  (8, '浅草屋寿司（运河上街店）', 1, 'https://img.meituan.net/msmerchant/cf3dff697bf7f6e11f4b79c4e7d989e4591290.jpg,https://img.meituan.net/msmerchant/0b463f545355c8d8f021eb2987dcd0c8567811.jpg,https://img.meituan.net/msmerchant/c3c2516939efaf36c4ccc64b0e629fad587907.jpg', '运河上街', '拱墅区金华路80号运河上街B1', 120.150526, 30.325231, 88, 2406, 1206, 46, 24.0500, '2026-10-13 12:24:02', '2026-10-09 12:24:02', ' 11:00-21:30', 1, '2021-12-22 19:51:06', '2026-10-13 12:24:02'),
  (9, '羊老三羊蝎子牛仔排北派炭火锅(运河上街店)', 1, 'https://p0.meituan.net/biztone/163160492_1624251899456.jpeg,https://img.meituan.net/msmerchant/e478eb16f7e31a7f8b29b5e3bab6de205500837.jpg,https://img.meituan.net/msmerchant/6173eb1d18b9d70ace7fdb3f2dd939662884857.jpg', '运河上街', '台州路2号运河上街购物中心F5', 120.150598, 30.325251, 101, 2763, 1363, 44, 29.0300, '2026-10-13 12:24:02', '2026-10-11 12:24:02', '11:00-21:30', 1, '2021-12-22 19:53:59', '2026-10-13 12:24:02'),
  (10, '开乐迪KTV（运河上街店）', 2, 'https://p0.meituan.net/joymerchant/a575fd4adb0b9099c5c410058148b307-674435191.jpg,https://p0.meituan.net/merchantpic/68f11bf850e25e437c5f67decfd694ab2541634.jpg,https://p0.meituan.net/dpdeal/cb3a12225860ba2875e4ea26c6d14fcc197016.jpg', '运河上街', '台州路2号运河上街购物中心F4', 120.149093, 30.324666, 67, 26891, 902, 37, 36.9600, '2026-10-13 12:24:02', '2026-10-13 12:24:02', '00:00-24:00', 1, '2021-12-22 20:25:16', '2026-10-13 12:24:02'),
  (11, 'INLOVE KTV(水晶城店)', 2, 'https://p0.meituan.net/dpmerchantpic/53e74b200211d68988a4f02ae9912c6c1076826.jpg,https://qcloud.dpfile.com/pc/4iWtIvzLzwM2MGgyPu1PCDb4SWEaKqUeHm--YAt1EwR5tn8kypBcqNwHnjg96EvT_Gd2X_f-v9T8Yj4uLt25Gg.jpg,https://qcloud.dpfile.com/pc/WZsJWRI447x1VG2x48Ujgu7vwqksi_9WitdKI4j3jvIgX4MZOpGNaFtM93oSSizbGybIjx5eX6WNgCPvcASYAw.jpg', '水晶城', '上塘路458号水晶城购物中心6层', 120.15853, 30.310002, 75, 35977, 5684, 47, 30.6100, '2026-10-13 12:24:02', '2026-10-10 12:24:02', '11:30-06:00', 1, '2021-12-22 20:29:02', '2026-10-13 12:24:02'),
  (12, '魅(杭州远洋乐堤港店)', 2, 'https://p0.meituan.net/dpmerchantpic/63833f6ba0393e2e8722420ef33f3d40466664.jpg,https://p0.meituan.net/dpmerchantpic/ae3c94cc92c529c4b1d7f68cebed33fa105810.png,', '远洋乐堤港', '丽水路58号远洋乐堤港F4', 120.14983, 30.31211, 88, 6444, 235, 46, 32.3300, '2026-10-13 12:24:02', '2026-10-12 12:24:02', '10:00-02:00', 1, '2021-12-22 20:34:34', '2026-10-13 12:24:02'),
  (13, '讴K拉量贩KTV(北城天地店)', 2, 'https://p1.meituan.net/merchantpic/598c83a8c0d06fe79ca01056e214d345875600.jpg,https://qcloud.dpfile.com/pc/HhvI0YyocYHRfGwJWqPQr34hRGRl4cWdvlNwn3dqghvi4WXlM2FY1te0-7pE3Wb9_Gd2X_f-v9T8Yj4uLt25Gg.jpg,https://qcloud.dpfile.com/pc/F5ZVzZaXFE27kvQzPnaL4V8O9QCpVw2nkzGrxZE8BqXgkfyTpNExfNG5CEPQX4pjGybIjx5eX6WNgCPvcASYAw.jpg', 'D32天阳购物中心', '湖州街567号北城天地5层', 120.130453, 30.327655, 58, 18997, 1857, 41, 24.8500, '2026-10-13 12:24:02', '2026-10-09 12:24:02', '12:00-02:00', 1, '2021-12-22 20:38:54', '2026-10-13 12:24:02'),
  (14, '星聚会KTV(拱墅区万达店)', 2, 'https://p0.meituan.net/dpmerchantpic/f4cd6d8d4eb1959c3ea826aa05a552c01840451.jpg,https://p0.meituan.net/dpmerchantpic/2efc07aed856a8ab0fc75c86f4b9b0061655777.jpg,https://qcloud.dpfile.com/pc/zWfzzIorCohKT0bFwsfAlHuayWjI6DBEMPHHncmz36EEMU9f48PuD9VxLLDAjdoU_Gd2X_f-v9T8Yj4uLt25Gg.jpg', '北部新城', '杭行路666号万达广场C座1-2F', 120.128958, 30.337252, 60, 17771, 685, 47, 31.1600, '2026-10-13 12:24:02', '2026-10-11 12:24:02', '10:00-22:00', 1, '2021-12-22 20:48:54', '2026-10-13 12:24:02');

-- 校验：门店数与类型数应与演示库一致（30 家 / 10 个类型）
SELECT COUNT(*) AS shops, SUM(id BETWEEN 1 AND 16) AS core_shops FROM `llmall-shop`.`shop`;
SELECT COUNT(*) AS types FROM `llmall-shop`.`shop_type`;
