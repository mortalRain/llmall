-- =====================================================================
-- 34-shop-favorite.sql
-- 34 · 门店收藏关系表（llmall-shop.shop_favorite）
--
-- 执行：mysql -uroot -p < 34-shop-favorite.sql
-- 位置：必须早于/同批于应用发布（应用启动后 POST /shops/{id}/favorite 依赖该表）。
--
-- ============================ 一、这份脚本做什么 ============================
-- 1) 在 llmall-shop 库建立 shop_favorite：一行 = "某用户收藏了某门店"；
--    UNIQUE uk_user_shop(user_id, shop_id) 同时是"重复收藏幂等"的落库闸门；
--    KEY idx_shop(shop_id) 支撑"某门店被收藏多少 / 按门店清理"。
-- 2) 不插入任何演示收藏：收藏是用户行为，预置"Jack 收藏了谁"会让 E2E 的
--    "收藏 → 我的收藏出现 → 取消"用例无法区分本轮数据与种子数据。
--
-- ============================ 二、为什么是关系表而不是 shop.favorite 计数 ============================
-- 主路径有两个：
--   · 我的收藏：WHERE user_id = ? ORDER BY create_time DESC —— 需要用户维度明细；
--   · 取消收藏：WHERE user_id = ? AND shop_id = ? —— 需要能精确删除一行。
-- 计数字段只能回答"被收藏多少次"，既查不出"我收藏了哪些"，也无法撤销单个人的收藏。
-- 因此只建关系表；若以后要展示"收藏热度"，应另建统计表或由本表聚合，不改本表语义。
--
-- ============================ 三、影响面 ============================
--   · 新增 llmall-shop.shop_favorite 一张表（CREATE TABLE IF NOT EXISTS，已存在则跳过）。
--   · 不修改 shop / shop_staff / 任何既有表结构与数据。
--   · 不加物理外键：user_id 是 llmall-user.user.id（跨库逻辑外键），
--     shop_id 是 llmall-shop.shop.id；服务间物理外键会破坏"一服务一库"的边界。
--   · 未执行本脚本就发布应用：收藏接口会因表不存在而 500；门店浏览/下单/核销不受影响。
--
-- ============================ 四、执行与核对 ============================
--   mysql -uroot -p < 34-shop-favorite.sql
--   SHOW CREATE TABLE `llmall-shop`.`shop_favorite`;
--   期望：PRIMARY KEY(id)、UNIQUE KEY uk_user_shop(user_id, shop_id)、KEY idx_shop(shop_id)
--   SELECT COUNT(*) FROM `llmall-shop`.`shop_favorite`;   -- 期望 0（脚本不种收藏）
--
-- ============================ 五、回滚 ============================
--   DROP TABLE `llmall-shop`.`shop_favorite`;
-- 【注意】DROP 会丢掉所有用户的收藏关系；若只是取消某个人的收藏，应 DELETE 行而不是删表。
-- =====================================================================

USE `llmall-shop`;

CREATE TABLE IF NOT EXISTS `shop_favorite` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '收藏人（llmall-user.user.id，跨库逻辑外键，无物理外键）',
  `shop_id`     BIGINT UNSIGNED NOT NULL COMMENT '被收藏门店（llmall-shop.shop.id）',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_shop` (`user_id`, `shop_id`) COMMENT '同一用户对同一门店只有一条收藏（幂等闸门）',
  KEY `idx_shop` (`shop_id`) COMMENT '按门店统计/清理收藏'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '门店收藏关系（用户 × 门店）';

-- 核对：表内不应有种子行（收藏由用户在页面上产生）
SELECT COUNT(*) AS favorite_rows FROM `shop_favorite`;
