-- =====================================================================
-- 00-create-databases.sql
-- 00 · 建库脚本（一个服务一个库）
--
-- 执行：mysql -uroot -p < 00-create-databases.sql
--
-- ============================ 一、功能 ============================
-- 建出本项目新增的 4 个业务库（llmall-shop / llmall-promotion / llmall-content / llmall-search）
-- 与 Seata Server 自己的会话库（seata），共 5 个；库已存在则整体跳过。
--
-- ============================ 二、背景 ============================
-- 原商城工程只有 llmall-user / llmall-item / llmall-cart / llmall-trade / llmall-pay 五个库，
-- 本地生活新增的门店域、营销域、内容域、搜索域没有归属库；Seata Server 的 store.mode=db
-- 也需要一个独立库承载会话表。为避免"某个域建表时报 ERROR 1049 Unknown database"，
-- 把建库动作全部收敛到本脚本、且必须是第一个执行。
--
-- ============================ 三、影响面 ============================
--   · 受影响：仅"库"这一层。同名库已存在时不改写字符集、不清空、不报错，直接跳过。
--   · 不受影响：任何库里的表与数据 —— 本脚本不含 CREATE TABLE / ALTER / INSERT。
--   · 不受影响：llmall-user / llmall-item / llmall-cart / llmall-trade / llmall-pay
--     由原商城脚本（上级目录 sql/llmall-*.sql）创建，本脚本不建、也不改。
--
-- ============================ 四、执行步骤 ============================
--   1. 确认实例可连接、当前账号有 CREATE 权限（不可跳过：无权限直接报 1044/1045，
--      而报错发生在第 1 句，后面 4 个库全不会建出来）。
--   2. 执行：mysql -uroot -p < 00-create-databases.sql
--   3. 核对（期望 5 行）：
--        SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
--         WHERE SCHEMA_NAME IN ('llmall-shop','llmall-promotion','llmall-content','llmall-search','seata');
-- 幂等：CREATE DATABASE IF NOT EXISTS，可重复执行。
--
-- ============================ 五、前置依赖 ============================
--   1) 无脚本前置依赖，本文件是 sql/merged 执行清单的第一步。
--   2) 顺序反了会怎样：01-undo-log-all.sql / 02-shared-infra.sql / 03-seata-server.sql
--      都靠 USE 切库，库不存在时这些脚本报 ERROR 1049 Unknown database；
--      01/02 是多库脚本，会在中途断掉，留下"建了一半"的状态（前面几个库有表、后面没有）。
--   3) 03-seata-server.sql 的三张全局表（global_table / branch_table / lock_table）
--      用 Seata 发行包 script/server/db/mysql.sql 建，勿手写；本脚本只把 seata 库建出来。
--
-- ============================ 六、回滚方案 ============================
-- DROP DATABASE `llmall-shop`;    -- promotion / content / search / seata 同理
-- 【不可逆】库中一旦写入数据，DROP DATABASE 无法还原，只能靠备份恢复。
--   仅当这 5 个库"刚建出来、还没跑过 10~41 脚本"时才可直接 DROP；
--   已建表有数据时必须先备份：
--     mysqldump -uroot -p --databases llmall-shop llmall-promotion llmall-content \
--                                  llmall-search seata > backup-databases.sql
--
-- ============================ 附、风险等级 ============================
-- 低 —— 纯建库，5 条语句全部带 IF NOT EXISTS，重复执行、顺序调换、甚至误在生产执行，
--       都不会改写或删除任何已有对象；判据：最坏结果只是多出几个空库，用 DROP DATABASE
--       即可自动恢复，不存在"改错后无法回到原状"的路径。
-- =====================================================================

-- 新增业务库
CREATE DATABASE IF NOT EXISTS `llmall-shop`      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; -- 门店域
CREATE DATABASE IF NOT EXISTS `llmall-promotion` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; -- 营销域
CREATE DATABASE IF NOT EXISTS `llmall-content`   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; -- 内容域
CREATE DATABASE IF NOT EXISTS `llmall-search`    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; -- 搜索域（热词/历史）

-- Seata Server 会话库（三张全局表用 Seata 发行包 script/server/db/mysql.sql 建，勿手写）
CREATE DATABASE IF NOT EXISTS `seata`        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 库 ↔ 服务对照（与 sql/merged/README.md 第一节一致）
--   llmall-user      → llmall-user-service        llmall-shop      → llmall-shop-service
--   llmall-item      → llmall-item-service        llmall-cart      → llmall-cart-service
--   llmall-trade     → llmall-trade-service（订单域）  llmall-pay      → llmall-pay-service
--   llmall-promotion → llmall-promotion-service   llmall-content   → llmall-content-service
--   llmall-search    → llmall-search-service      seata            → Seata Server
--
-- 【注意】订单库名是 llmall-trade，不存在 llmall-order 这个库：
--   llmall-trade-service 的 application.yaml 里 llmall.db.database = llmall-trade，
--   本目录所有订单相关脚本（02 / 31 / 50 / 51 / 52 / 99）写的也都是 llmall-trade。
--   设计文档里的 llmall-order 只是命名建议 —— 真要改名，必须"建库 + 全目录脚本 + 应用配置"
--   一起替换，只改其中一处会让服务连不上库、或脚本报表不存在。
--   若确认要改名，下面这句是建库语句，其余脚本与配置需同步修改：
-- CREATE DATABASE IF NOT EXISTS `llmall-order` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
