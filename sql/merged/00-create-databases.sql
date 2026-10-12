-- ============================================================
-- 00 · 建库脚本（一个服务一个库）
-- 执行顺序：本文件 → 01 → 02 → 10 ~ 40
-- 说明：llmall-user / llmall-item / llmall-cart / llmall-trade / llmall-pay 由原商城脚本创建，
--       本文件只负责【新增库】与【Seata 库】。
-- ============================================================

-- 新增业务库
CREATE DATABASE IF NOT EXISTS `llmall-shop`      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; -- 门店域
CREATE DATABASE IF NOT EXISTS `llmall-promotion` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; -- 营销域
CREATE DATABASE IF NOT EXISTS `llmall-content`   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; -- 内容域
CREATE DATABASE IF NOT EXISTS `llmall-search`    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; -- 搜索域（热词/历史）

-- Seata Server 会话库（三张全局表用 Seata 发行包 script/server/db/mysql.sql 建，勿手写）
CREATE DATABASE IF NOT EXISTS `seata`        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 库 ↔ 服务对照（写进 README，别人一看就懂）
--   llmall-user      → llmall-user-service        llmall-shop      → llmall-shop-service
--   llmall-item      → llmall-item-service        llmall-cart      → llmall-cart-service
--   llmall-order     → order-service(原 llmall-trade-service)  llmall-pay → llmall-pay-service
--   llmall-promotion → llmall-promotion-service   llmall-content   → llmall-content-service
--   llmall-search    → llmall-search-service      seata        → Seata Server
--
-- ⚠️ 注意：原 llmall-trade-service 用的是 llmall-trade 库。本次合并把"订单"作为独立领域，
--    建议将库重命名为 llmall-order（更贴领域名）。若暂时不想改，把下面这句执行掉即可：
-- CREATE DATABASE IF NOT EXISTS `llmall-order` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
-- 然后把 llmall-trade 的表结构导入 llmall-order（见 README 的迁移步骤）。
