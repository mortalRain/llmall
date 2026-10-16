-- =====================================================================
-- 30-content.sql
-- 30 · 内容域建表（llmall-content-service / llmall-content）
--
-- 表结构由点评的 tb_blog、tb_blog_comments、tb_follow 改造而来。
--
-- 执行：mysql -uroot -p < 30-content.sql
--
-- ============================ 一、功能 ============================
-- 在 llmall-content 库建出内容域五张表：blog（探店笔记/评价）、blog_comments（评论）、
-- blog_like（点赞）、follow（关注关系）、feed_inbox（Feed 流收件箱）。
--
-- ============================ 二、背景 ============================
-- 原点评表只够"发一篇笔记"，不足以支撑本项目的三条业务链路，因此做三处改造：
--   ① blog 增加 score（评分）与 status：评分是"评价 → 门店热度"唯一的输入，
--      热度公式 base = score/10*4 + log10(comments+1)*3 + log10(sold+1)*3 直接读它；
--      缺这一列，评价写得再多门店热度也不会变。
--   ② 新增 blog_like：uk_blog_user(blog_id, user_id) 唯一索引让"点赞"天然幂等
--      （重复点赞被唯一键顶掉，不需要先查后插），同时支撑点赞排行榜。
--      缺唯一键时并发点赞会给同一条笔记记出多行，liked 计数只增不减。
--   ③ 新增 feed_inbox：Feed 流"推模式"收件箱（关注推送），
--      uk_user_blog(user_id, blog_id) 保证重复推送幂等；
--      idx_user_id_desc(user_id, id DESC) 是滚动分页（按 id 倒序游标）的支撑索引。
--   ④ 关注数/粉丝数的计数落在 llmall-user.user_info（跨服务更新走事件最终一致），
--      本库只存关注关系，不存计数 —— 两处都存会出现"计数与关系对不上"。
--
-- ============================ 三、影响面 ============================
--   · 受影响：llmall-content 库新增 5 张表（全部 CREATE TABLE IF NOT EXISTS，已存在则跳过）。
--   · 不受影响：任何库的既有表结构与数据 —— 本脚本不含 ALTER / INSERT / UPDATE / DELETE。
--   · 不受影响：llmall-content 库里的 undo_log / message_log，分别由
--     01-undo-log-all.sql / 06-content-message-log.sql 建。
--   · 【注意】本脚本建出的 blog.images 是 NOT NULL 且无默认值，这一形态会让
--     "不带图片发布评价"插入失败；因此本脚本之后必须再跑 23-content-image-default.sql 修正它，
--     且 23 号脚本必须排在本脚本之后（本目录唯一一处破数字序的地方）。
--   · 建完是空表：内容数据要靠 50 / 51-seed 脚本（演示数据）灌入。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认 00-create-databases.sql 已执行（不可跳过：llmall-content 库由它建，
--      库不存在时报 ERROR 1049 Unknown database）。
--   2. 执行：mysql -uroot -p < 30-content.sql
--   3. 核对（期望 5 行）：
--        SELECT TABLE_NAME FROM information_schema.TABLES
--         WHERE TABLE_SCHEMA = 'llmall-content'
--           AND TABLE_NAME IN ('blog','blog_comments','blog_like','follow','feed_inbox');
--   4. 核对幂等与分页索引已建（缺失会让重复点赞/重复推送写进多行）：
--        SHOW INDEX FROM `llmall-content`.`blog_like`;    -- 期望有 uk_blog_user
--        SHOW INDEX FROM `llmall-content`.`feed_inbox`;   -- 期望有 uk_user_blog、idx_user_id_desc
--   5. 紧跟其后执行 23-content-image-default.sql（修正 blog.images，不可跳过）。
--   6. （默认不执行）脚本末尾"数据迁移"段：从 dianping_src 迁点评内容，默认注释状态。
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
--
-- ============================ 五、前置依赖 ============================
--   1) 00-create-databases.sql（建 llmall-content 库）。
--   2) 应用侧依赖：内容域服务启动即读这些表。
--   3) 下游依赖：23-content-image-default.sql（改 blog.images，必须在其后）；
--      06-content-message-log.sql（内容域消息表）；
--      50 / 51-seed 脚本（写 blog / blog_comments / blog_like / follow / feed_inbox）；
--      50 号脚本还会按 blog 的评分与时间重算门店热度（写 llmall-shop.shop）。
--   4) 顺序反了会怎样：库不存在 → ERROR 1049；
--      23 先于本脚本 → ERROR 1146 Table 'llmall-content.blog' doesn't exist；
--      50 / 51 先于本脚本 → 插数据时逐条报表不存在，且它们是"先删后插"，
--      会在半途留下不完整的演示数据状态。
--
-- ============================ 六、回滚方案 ============================
-- 应用回退到"不读内容域表"的版本后执行（注意先回滚 23 号脚本对 images 的改动）：
--   DROP TABLE `llmall-content`.`feed_inbox`;
--   DROP TABLE `llmall-content`.`follow`;
--   DROP TABLE `llmall-content`.`blog_like`;
--   DROP TABLE `llmall-content`.`blog_comments`;
--   DROP TABLE `llmall-content`.`blog`;
-- 【注意】DROP 是"结构 + 数据"一起丢：
--   · 只建表还没写数据 → 直接 DROP 属可逆（重跑 30 + 23 即恢复）；
--   · 已写入评价/评论 → 评价是门店热度的唯一来源，DROP 后热度会永久停在旧值，
--     且"某条评价是谁写的"再也无法追溯，属于不可逆。
--     DROP 前必须导出：
--       mysqldump -uroot -p llmall-content blog blog_comments blog_like follow feed_inbox \
--                                > backup-30-content.sql
--   · 连锁影响：门店热度由评价事件驱动，本表清空不会自动回退热度分，
--     需要人工用 llmall-shop.shop 的现有值重算或接受旧值。
--
-- ============================ 附、风险等级 ============================
-- 低 —— 五张表全部是 IF NOT EXISTS 新建，不改写任何存量数据，可 DROP 完整回滚。
--       判据：改错（重复执行、误在别的库执行）只会跳过或新建空表，不存在破坏存量数据的
--       路径；表内数据的丢失风险来自 50 / 51 的灌数与业务写入，不属于本脚本。
-- =====================================================================

USE `llmall-content`;

CREATE TABLE IF NOT EXISTS `blog` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `shop_id`     BIGINT          NOT NULL COMMENT '关联门店 id',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '作者 id',
  `title`       VARCHAR(255)    NOT NULL COMMENT '标题',
  `images`      VARCHAR(2048)   NOT NULL COMMENT '图片，多张以逗号分隔',
  `content`     VARCHAR(2048)   NOT NULL COMMENT '探店/评价正文',
  `score`       TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '评分 1~5（用于计算门店热度）',
  `liked`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数（同时写 Redis ZSet 排行）',
  `comments`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数',
  `status`      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 待审核 1 已发布 2 已屏蔽',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_shop` (`shop_id`, `status`),
  KEY `idx_user` (`user_id`, `create_time`),
  KEY `idx_liked` (`liked`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '探店笔记/评价表';

CREATE TABLE IF NOT EXISTS `blog_comments` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `blog_id`     BIGINT UNSIGNED NOT NULL,
  `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父评论 id，0 为一级评论',
  `answer_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '被回复的评论 id，0 为无',
  `content`     VARCHAR(255)    NOT NULL COMMENT '回复内容',
  `liked`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数',
  `status`      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 正常 1 被举报 2 禁止查看',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_blog` (`blog_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '笔记评论表';

-- 点赞：唯一索引保证"同一用户对同一笔记只有一条"，天然幂等
CREATE TABLE IF NOT EXISTS `blog_like` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `blog_id`     BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blog_user` (`blog_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '笔记点赞表';

-- 关注关系（关注数/粉丝数的计数在 user_info，跨服务更新走事件最终一致）
CREATE TABLE IF NOT EXISTS `follow` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '关注发起人',
  `follow_user_id` BIGINT UNSIGNED NOT NULL COMMENT '被关注的人',
  `create_time`    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow` (`user_id`, `follow_user_id`),
  KEY `idx_followed` (`follow_user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户关注表';

-- Feed 收件箱（推模式：发布笔记时写入粉丝的收件箱；大 V 走拉模式，后续按需扩展）
CREATE TABLE IF NOT EXISTS `feed_inbox` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '收件人（粉丝）',
  `blog_id`     BIGINT UNSIGNED NOT NULL COMMENT '笔记 id',
  `author_id`   BIGINT UNSIGNED NOT NULL COMMENT '作者 id',
  `create_time` TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_blog` (`user_id`, `blog_id`) COMMENT '重复推送幂等',
  KEY `idx_user_id_desc` (`user_id`, `id` DESC) COMMENT '滚动分页用（按 id 倒序游标）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Feed 流收件箱';

-- ------------------------------------------------------------
-- 数据迁移（点评内容）
-- ------------------------------------------------------------
-- INSERT INTO `llmall-content`.`blog` (id, shop_id, user_id, title, images, content, liked, comments, create_time, update_time)
-- SELECT id, shop_id, user_id, title, images, content, liked, comments, create_time, update_time FROM `dianping_src`.`tb_blog`;
--
-- INSERT INTO `llmall-content`.`blog_comments` (id, user_id, blog_id, parent_id, answer_id, content, liked, status, create_time, update_time)
-- SELECT id, user_id, blog_id, parent_id, answer_id, content, liked, status, create_time, update_time FROM `dianping_src`.`tb_blog_comments`;
--
-- INSERT INTO `llmall-content`.`follow` (id, user_id, follow_user_id, create_time)
-- SELECT id, user_id, follow_user_id, create_time FROM `dianping_src`.`tb_follow`;
