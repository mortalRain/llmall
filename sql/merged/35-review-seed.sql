-- =====================================================================
-- 35-review-seed.sql
-- 35 · 门店评价种子（llmall-content.blog）—— 演示 / 冒烟库专用
--
-- 执行：mysql -uroot -p < 35-review-seed.sql
-- 位置：必须在 30-content.sql（建 blog 表）与 55-seed-core-shops.sql（门店 1~14）之后；
--       建议放在 50/51 四步演示数据之后，避免被后续 seed 的 DELETE 区间清掉。
--
-- ============================ 一、这份脚本做什么 ============================
-- 给 5 家演示门店（1 / 3 / 4 / 10 / 11）各补 6 条真实中文评价，共 30 条：
--   · shop_id / user_id 使用演示库已有用户（1 Jack、2 Rose、3 Hope），JOIN user 表校验，
--     用户不存在则对应行不插入（宁缺勿错，不制造指向不存在用户的评价）；
--   · score 4~5；liked / comments 为合理的小数值；status=1（已发布，前台可见）；
--   · create_time 分布在近 30 天内，满足"最近评价"的展示与排序；
--   · title/content 均为可读中文，content 长度约 20~80 字，用于门店详情的评价区。
--
-- 【为什么需要种子】演示库的 blog 表为空（原商城/点评数据未迁入），门店详情页的评价区
-- 会一直是空态，无法演示"评分/内容/时间"的展示，也无法让 `/blogs/shop/{shopId}` 返回数据。
--
-- ============================ 二、幂等与不覆盖 ============================
-- 每条 INSERT 都带 NOT EXISTS 判据：(shop_id, user_id, title) 已存在则跳过。
-- 因此：
--   · 重复执行不会新增重复评价；
--   · 不 UPDATE 已有行，不覆盖运营/用户后来改过的内容；
--   · 不使用写死的主键 id，交给 AUTO_INCREMENT，避免和真实评价 id 冲突。
-- 【注意】判据包含 title：同一用户在同一门店可以有多条不同标题的评价，这是允许的；
--   只有"同店同人同标题"才视为本脚本已种过。
--
-- ============================ 三、影响面 ============================
--   · 仅写 llmall-content.blog，新增最多 30 行。
--   · 不修改 blog 既有行，不写 blog_comments / blog_like / shop 聚合字段。
--   · 不修改任何订单、支付、用户、门店数据。
--   · 评价以 status=1 写入，会出现在 GET /blogs/shop/{shopId} 的分页结果中。
--
-- ============================ 四、执行与核对 ============================
--   mysql -uroot -p < 35-review-seed.sql
--   期望：5 家店各 6 条，总计 30 条
--   SELECT shop_id, COUNT(*) FROM `llmall-content`.`blog`
--    WHERE shop_id IN (1,3,4,10,11) AND status = 1 GROUP BY shop_id;
--   接口核对：curl 'http://localhost:8080/blogs/shop/10?page=1&size=10'
--   应返回 total >= 6、list 非空，且每项含 score / content / createTime。
--
-- ============================ 五、回滚 ============================
-- 本脚本没有独立的"批次标记"，回滚必须按 (shop_id, title) 精确匹配脚本里 UNION ALL
-- 的 30 个标题（示例：先删 1 号店的第一条）：
--   DELETE FROM `llmall-content`.`blog`
--    WHERE shop_id = 1 AND title = '老牌茶餐厅的稳定发挥';
-- 其余标题照抄脚本中的 UNION ALL 清单逐条删即可。
-- 【不要】按 create_time 区间或 user_id 批量删：真实评价同样会落在这些条件里。
-- 演示环境也可直接重建 llmall-content 库；生产库不得执行本脚本。
-- =====================================================================

USE `llmall-content`;

INSERT INTO `blog`
  (`shop_id`, `user_id`, `title`, `images`, `content`, `score`, `liked`, `comments`,
   `status`, `create_time`, `update_time`)
SELECT s.shop_id, s.user_id, s.title, '', s.content, s.score, s.liked, s.comments,
       1, NOW() - INTERVAL s.days DAY, NOW() - INTERVAL s.days DAY
FROM (
  -- 1 号店（茶餐厅）：6 条，覆盖不同用户与不同关注点
  SELECT 1 AS shop_id, 1 AS user_id, '老牌茶餐厅的稳定发挥' AS title,
         '工作日中午过来的，出餐很快，烧腊拼盘和例汤都在线，人均五十出头，适合周边上班族解决午饭。' AS content,
         5 AS score, 28 AS liked, 3 AS comments, 2 AS days
  UNION ALL SELECT 1, 2, '带爸妈来吃早茶很合适',
         '虾饺和凤爪是现蒸的，皮薄馅足；服务员会主动添茶，环境不算新但很干净，老人吃得舒服。', 5, 19, 2, 4
  UNION ALL SELECT 1, 3, '双人套餐分量足',
         '双人套餐两个女生吃不完，最后打包了半份烧鹅。味道偏广式，咸淡刚好，下次还会再来。', 4, 12, 1, 6
  UNION ALL SELECT 1, 1, '宵夜时段也营业',
         '晚上十点后还能点单，牛河锅气很足，配一杯冻柠茶很解腻。店里信号一般，扫码点单要耐心。', 4, 9, 0, 9
  UNION ALL SELECT 1, 2, '服务比味道更惊喜',
         '不小心把饮料碰洒了，店员马上过来清理还补了一杯。菜品没有踩雷，冲这个服务也愿意复购。', 5, 21, 2, 12
  UNION ALL SELECT 1, 3, '停车稍微有点绕',
         '味道和分量都挑不出毛病，就是商场地下车库入口不太好找，建议第一次来留出十分钟找车位。', 4, 7, 1, 15

  -- 3 号店（正餐）：6 条
  UNION ALL SELECT 3, 2, '朋友聚会选了这家',
         '六个人点了满满一桌，招牌菜基本都试了，没有明显短板。上菜节奏可以再快一点，整体值得推荐。', 5, 31, 4, 1
  UNION ALL SELECT 3, 1, '环境适合拍照',
         '店里灯光偏暖，卡座间距大，聊天不吵。菜品摆盘精致，价格略高，但纪念日来一次很合适。', 5, 24, 2, 3
  UNION ALL SELECT 3, 3, '工作日套餐性价比高',
         '午市套餐比单点便宜不少，主食可以续，汤品每天不一样。附近写字楼的人来得挺多，建议错峰。', 4, 16, 1, 5
  UNION ALL SELECT 3, 1, '口味偏清淡',
         '整体调味不重，能吃出食材本身的味道。重口的朋友可能觉得不够刺激，我个人很喜欢。', 4, 11, 0, 8
  UNION ALL SELECT 3, 2, '甜品是意外之喜',
         '本来冲着主菜来的，结果被餐后的桂花糕圈粉，甜度克制，临走还打包了一份。', 5, 18, 2, 11
  UNION ALL SELECT 3, 3, '排队时间可以接受',
         '周末晚市等了二十分钟左右，等位区有茶水和小食。翻台挺快，服务员态度也一直在线。', 4, 8, 1, 14

  -- 4 号店（西餐）：6 条
  UNION ALL SELECT 4, 1, '纪念日来这里没错',
         '提前电话预约了靠窗位，到店还送了小蛋糕。牛排火候控制得不错，氛围和服务都对得起价格。', 5, 42, 5, 2
  UNION ALL SELECT 4, 3, '摆盘精致味道在线',
         '前菜的沙拉很新鲜，主菜的酱汁不会盖住食材本味。上菜时会介绍吃法，体验感很好。', 5, 27, 3, 5
  UNION ALL SELECT 4, 2, '约会圣地但略贵',
         '两个人点了三菜一甜品接近四百，味道确实好。适合特殊日子，日常来吃会有点心疼钱包。', 4, 23, 2, 7
  UNION ALL SELECT 4, 1, '服务细节到位',
         '看到我们带了小朋友，主动换了靠里的位置并拿来儿童餐具，这种细节很加分。', 5, 15, 1, 10
  UNION ALL SELECT 4, 3, '牛排推荐五分熟',
         '第一次来听服务员建议点了五分熟，切开汁水很足。配的烤蔬菜也不敷衍，整体很满意。', 5, 20, 2, 13
  UNION ALL SELECT 4, 2, '停车方便',
         '门口就有停车位，消费可以抵扣两小时。晚上过去人不多，不用排队，适合临时约饭。', 4, 9, 0, 16

  -- 10 号店（KTV，演示账号任职门店）：6 条，E2E 依赖
  UNION ALL SELECT 10, 1, '包厢音效不错',
         '公司团建订了大包厢，麦克风没有啸叫，歌单更新也及时。果盘和小食续得挺快，整体氛围很好。', 5, 35, 4, 1
  UNION ALL SELECT 10, 2, '周末下午场很划算',
         '下午场价格比晚场便宜一半，学生党友好。包厢隔音一般，隔壁唱高音时能听到一点。', 4, 22, 2, 2
  UNION ALL SELECT 10, 3, '服务员响应很快',
         '点歌屏卡了一次，按服务铃后两分钟就有人来处理。酒水价格透明，没有隐形消费。', 5, 17, 1, 4
  UNION ALL SELECT 10, 1, '生日布置很用心',
         '提前说了过生日，店里帮忙布置了气球还送了长寿面。朋友都说氛围拉满，下次还来。', 5, 46, 6, 6
  UNION ALL SELECT 10, 2, '曲库挺全',
         '老歌和新歌都能找到，冷门乐队的专辑也有。音响低音偏重，调一下均衡器会舒服很多。', 4, 13, 1, 9
  UNION ALL SELECT 10, 3, '停车和夜宵都方便',
         '楼下停车位充足，唱完出来旁边就有夜宵摊。包厢最低消费写得清楚，结账没有额外费用。', 4, 10, 0, 12

  -- 11 号店（KTV）：6 条
  UNION ALL SELECT 11, 2, '连锁KTV的稳定体验',
         '环境比想象中新，走廊没有烟味。中包容纳八个人不挤，团购套餐包含的酒水也够喝。', 4, 26, 3, 3
  UNION ALL SELECT 11, 3, '音效比上次好',
         '换了新话筒，人声清晰不少。点歌系统支持手机扫码，不用挤在屏幕前，体验提升明显。', 5, 18, 2, 5
  UNION ALL SELECT 11, 1, '工作日人少体验好',
         '周三晚上去的，基本不用等位，服务员也更从容。同样的价格比周末舒服太多。', 5, 14, 1, 8
  UNION ALL SELECT 11, 2, '生日局首选',
         '提前订包厢可以自带蛋糕，店里提供盘子和蜡烛。布置简单但氛围到位，性价比很高。', 5, 21, 2, 11
  UNION ALL SELECT 11, 3, '小吃味道超预期',
         '本来没对KTV的小吃抱期待，结果炸鸡翅和薯条都是现炸的，比不少快餐店还好吃。', 5, 16, 1, 14
  UNION ALL SELECT 11, 1, '结账速度很快',
         '扫码结账后两分钟就有人来核对，没有拖时间。会员还能积分，整体流程很顺畅。', 4, 8, 0, 18
) s
JOIN `llmall-user`.`user` u ON u.id = s.user_id
WHERE NOT EXISTS (
  SELECT 1 FROM `blog` b
  WHERE b.shop_id = s.shop_id
    AND b.user_id = s.user_id
    AND b.title = s.title
);

-- 核对 1：5 家店各 6 条（重复执行后仍是 6 条，不会翻倍）
SELECT shop_id, COUNT(*) AS review_rows, MIN(score) AS min_score, MAX(score) AS max_score
FROM `blog`
WHERE shop_id IN (1, 3, 4, 10, 11) AND status = 1
GROUP BY shop_id
ORDER BY shop_id;

-- 核对 2：确认 content 不是空串、评分在 4~5
SELECT COUNT(*) AS bad_rows
FROM `blog`
WHERE shop_id IN (1, 3, 4, 10, 11)
  AND status = 1
  AND (content = '' OR score NOT BETWEEN 4 AND 5);
