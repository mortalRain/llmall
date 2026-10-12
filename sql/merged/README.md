# sql/merged · 库表脚本执行说明

## 一、库 ↔ 服务对照（一个服务一个库）

| 库 | 服务 | 端口 | 脚本来源 |
|---|---|---|---|
| `llmall-user` | llmall-user-service | 8084 | 原商城 `llmall-user.sql` + **本目录 `21-user-alter.sql`** |
| `llmall-shop` | llmall-shop-service | 8087 | **本目录 `10-shop.sql`**（由点评 tb_shop 改造） |
| `llmall-item` | llmall-item-service | 8081 | 原商城 `llmall-item.sql` + **本目录 `11-item-alter.sql`** |
| `llmall-cart` | llmall-cart-service | 8082 | 原商城 `llmall-cart.sql` |
| `llmall-trade` | llmall-trade-service（订单域） | 8085 | 原商城 `llmall-trade.sql` + **本目录 `31-order-alter.sql`** |
| `llmall-pay` | llmall-pay-service | 8086 | 原商城 `llmall-pay.sql` |
| `llmall-promotion` | llmall-promotion-service | 8088 | **本目录 `20-promotion.sql`**（由点评 tb_voucher 系改造） |
| `llmall-content` | llmall-content-service | 8089 | **本目录 `30-content.sql`**（由点评 tb_blog 系改造） |
| `llmall-search` | llmall-search-service | 8093 | **本目录 `40-search.sql`** |
| `seata` | Seata Server | — | Seata 发行包 `script/server/db/mysql.sql` |

## 二、执行顺序（**按文件名数字顺序执行，不要跳号**）

```bash
# 0) 备份（重要）
mysqldump -uroot -p --databases llmall-user llmall-item llmall-cart llmall-trade llmall-pay > backup-$(date +%F).sql

# 1) 建库（9 个业务库 + 口径统一）
mysql -uroot -p < 00-create-databases.sql

# 2) 导入原有商城数据（首次部署才需要；文件在上一级 sql/ 目录）
mysql -uroot -p < ../llmall-user.sql
mysql -uroot -p < ../llmall-item.sql     # 约 35MB，耐心等
mysql -uroot -p < ../llmall-cart.sql
mysql -uroot -p < ../llmall-trade.sql
mysql -uroot -p < ../llmall-pay.sql

# 3) 分布式事务基础设施（undo_log 每个库一张 + 幂等/消息表）
mysql -uroot -p < 01-undo-log-all.sql
mysql -uroot -p < 02-shared-infra.sql

# 4) Seata Server 自己的库（seata）：1.4.2 建表 + 1.5.2 增量升级，两步都要跑
mysql -uroot -p < 03-seata-server.sql
mysql -uroot -p < 03b-seata-server-1.5.2-upgrade.sql

# 5) 各域建表 / 改造（数字即执行顺序）
mysql -uroot -p < 10-shop.sql
mysql -uroot -p < 11-item-alter.sql
mysql -uroot -p < 20-promotion.sql
mysql -uroot -p < 21-user-alter.sql
mysql -uroot -p < 22-user-time-default.sql
mysql -uroot -p < 23-content-image-default.sql
mysql -uroot -p < 30-content.sql
mysql -uroot -p < 31-order-alter.sql
mysql -uroot -p < 40-search.sql
mysql -uroot -p < 41-search-geo.sql

# 6) 演示数据（**仅演示环境**：脚本内含按 id 区间的 DELETE，不要在生产库执行）
mysql -uroot -p < 50-seed-local-life-data.sql
mysql -uroot -p < 51-seed-more-data.sql
mysql -uroot -p < 52-fix-orphan-pay-orders.sql

# 7) 可选：从原点评库迁移门店/笔记数据（需要外部库 dianping_src 已存在）
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS dianping_src DEFAULT CHARSET utf8mb4;"
# dianping_src 的建表数据在 D:\dsh\legacy\dianping-src 项目里（不在本仓库内）
mysql -uroot -p < 99-migrate-dianping-data.sql
```

> 上面没写 `dianping_src` 的 SQL 文件名是有意的：那份 dump 属于**另一个项目**（原点评工程），
> 不在本仓库内；不跑第 7 步不影响系统运行，前 6 步是完整可用的最小集合。

## 三、注意事项

1. **库名口径**：订单库就叫 `llmall-trade`（与 `llmall-trade-service/.../application.yaml` 的 `llmall.db.database` 一致），不存在 `llmall-order`。
2. **数据迁移语句默认是注释状态**（每个脚本末尾的 `INSERT INTO ... SELECT`）。先看一遍再取消注释——尤其是 `21-user-alter.sql` 里的用户迁移，密码策略要先定：**统一 BCrypt，历史数据重置密码**，不要留两套校验分支。
3. **`ALTER TABLE user ADD UNIQUE KEY uk_phone`** 可能因历史数据里 phone 重复/NULL 而失败：先 `SELECT phone, COUNT(*) FROM user GROUP BY phone HAVING COUNT(*) > 1;` 清洗后再加。
4. **MySQL 8 兼容性**：本目录脚本已去掉原点评脚本里的 `ZEROFILL` 和 `'0000-00-00'` 默认值（严格模式下会直接报错）。
5. 所有"改造型"脚本（`11/21/22/23/31/41`）都是 `ALTER TABLE`，**可重复执行的只有 `CREATE TABLE IF NOT EXISTS` 部分**；`ALTER` 重复执行会报 "Duplicate column/duplicate key"，属正常现象，忽略即可。
6. **改表结构前先在工作副本验证**：`sql/merged/` 是"从零重建"的脚本；对已经在跑的库做变更，请单独写增量脚本并先备份。
