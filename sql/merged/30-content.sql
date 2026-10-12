-- ============================================================
-- 30 · 内容域（llmall-content-service / llmall-content）
-- 来源：点评 tb_blog、tb_blog_comments、tb_follow
-- 改造点：
--   ① blog 增加 score（评分）与 status —— 评分是"评价 → 门店热度"的输入
--   ② 新增 blog_like：点赞幂等（唯一索引）+ 支撑点赞排行榜
--   ③ 新增 feed_inbox：Feed 流"推模式"收件箱（关注推送）
-- ============================================================

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
