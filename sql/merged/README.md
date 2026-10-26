# sql/merged · 库表脚本执行说明

> **本文件是执行顺序的唯一权威（single source of truth）。**
> 目录里共 **29 个 .sql 脚本**（+ 本说明文件），下面是**完整、按依赖排序**的执行清单（不是简单按文件名数字排——
> 有一处必须破数字序，见 2.1 的 `23` / `30`）。
> 其中 `52a` 是只读清点（不是变更脚本）、`63` 是演示 / 冒烟环境专用（生产库不得执行），两者在清单里都单列了用途。

## 一、库 ↔ 服务对照（一个服务一个库）

| 库 | 服务 | 端口 | 脚本来源 |
|---|---|---|---|
| `llmall-user` | llmall-user-service | 8084 | 原商城 `llmall-user.sql` + **本目录 `21-user-alter.sql`、`22-user-time-default.sql`** |
| `llmall-shop` | llmall-shop-service | 8087 | **本目录 `10-shop.sql`**（由点评 tb_shop 改造） |
| `llmall-item` | llmall-item-service | 8081 | 原商城 `llmall-item.sql` + **本目录 `11-item-alter.sql`** |
| `llmall-cart` | llmall-cart-service | 8082 | 原商城 `llmall-cart.sql` |
| `llmall-trade` | llmall-trade-service（订单域） | 8085 | 原商城 `llmall-trade.sql` + **本目录 `31-order-alter.sql`** |
| `llmall-pay` | llmall-pay-service | 8086 | 原商城 `llmall-pay.sql` |
| `llmall-promotion` | llmall-promotion-service | 8088 | **本目录 `20-promotion.sql`**（由点评 tb_voucher 系改造） |
| `llmall-content` | llmall-content-service | 8089 | **本目录 `30-content.sql`**（由点评 tb_blog 系改造） |
| `llmall-search` | llmall-search-service | 8093 | **本目录 `40-search.sql`** + **`41-search-geo.sql`** |
| `seata` | Seata Server | — | **本目录 `03-seata-server.sql`**（1.5.2）；从 1.4.2 升级用 **`03b-seata-server-1.5.2-upgrade.sql`** |

---

## 二、执行顺序（完整清单，按依赖排序）

> 全部脚本都可**重复执行**（2026-10 幂等化改造，见第四节）。
> 也就是说：这个顺序跑第二遍不会报错、不会改表结构；只有演示数据会被重算成同一份。

```bash
# 0) 备份（重要，顺序不能省）
mysqldump -uroot -p --databases llmall-user llmall-item llmall-cart llmall-trade llmall-pay \
                            llmall-shop llmall-promotion llmall-content llmall-search seata > backup-$(date +%F).sql

# ── 1) 建库（新增库 + seata 库；llmall-user/item/cart/trade/pay 由第 2 步的原商城脚本建） ──
mysql -uroot -p < 00-create-databases.sql

# ── 2) 原商城数据（首次部署才需要；文件在上一级 sql/ 目录） ──
mysql -uroot -p < ../llmall-user.sql
mysql -uroot -p < ../llmall-item.sql     # 约 35MB，耐心等
mysql -uroot -p < ../llmall-cart.sql
mysql -uroot -p < ../llmall-trade.sql
mysql -uroot -p < ../llmall-pay.sql

# ── 3) 分布式事务基础设施（undo_log 每个库一张 + 幂等/消息表） ──
mysql -uroot -p < 01-undo-log-all.sql
mysql -uroot -p < 02-shared-infra.sql

# ── 4) Seata Server 自己的库（seata）：1.5.2 全量建表 + 1.4.2→1.5.2 增量升级，两步都要跑 ──
mysql -uroot -p < 03-seata-server.sql
mysql -uroot -p < 03b-seata-server-1.5.2-upgrade.sql

# ── 5) 各域建表 / 改造 ──
mysql -uroot -p < 10-shop.sql                   # 5.1  门店域建表（shop / shop_type / shop_stat）
mysql -uroot -p < 55-seed-core-shops.sql        # 5.1b ★必须先于 50：补齐 14 家核心门店（id 1~14）与类型 1/2
                                                #     这批门店原本只来自 99 号点评迁移（依赖仓库外的库），
                                                #     不补则门店只有 16 家、套餐只有 80 个，MallE2E 直接红
mysql -uroot -p < 11-item-alter.sql             # 5.2  商品域改造（item 加 shop_id/type + 索引，建 stock_flow）
mysql -uroot -p < 20-promotion.sql              # 5.3  营销域建表（voucher / seckill_voucher / user_voucher）
mysql -uroot -p < 21-user-alter.sql             # 5.4  用户域改造（user 加 nick_name/icon + uk_phone，建 user_info/user_sign/points_flow）
mysql -uroot -p < 04-balance-flow.sql           # 5.5  用户域建表（balance_flow 资金流水，B2 幂等键）
mysql -uroot -p < 05-shop-message-log.sql       # 5.6  门店域建表（message_log 本地消息表，B4 可靠投递）
mysql -uroot -p < 06-content-message-log.sql    # 5.7  内容域建表（message_log 本地消息表，B4 生产端）
mysql -uroot -p < 22-user-time-default.sql      # 5.7  llmall-user.user 时间字段补默认值
mysql -uroot -p < 30-content.sql                # 5.8  内容域建表（blog / blog_comments / blog_like / follow / feed_inbox）
mysql -uroot -p < 23-content-image-default.sql  # 5.9  ⚠️ 依赖 5.8：blog 表由 30 号建立，23 号必须排在它后面
mysql -uroot -p < 31-order-alter.sql            # 5.10 交易域改造（order 加 order_type/shop_id/voucher_id/verify_code/verify_time + 索引）
mysql -uroot -p < 32-shop-staff.sql             # 5.10b 门店员工表 + 核销判权种子（Jack 为 1/10 号店 STAFF）
                                                #      ⚠️ 必须与应用同批上线：未建表时核销接口 fail-closed，员工校验取不到答案会全部拒绝
mysql -uroot -p < 40-search.sql                 # 5.11 搜索域建表（shop_search_doc / search_hot_word / search_history）
mysql -uroot -p < 41-search-geo.sql             # 5.12 ⚠️ 依赖 5.11：给 shop_search_doc 补 x/y
mysql -uroot -p < 62-align-dict-comments.sql    # 5.13 【存量库注释对齐；全新初始化可跳过】7 条纯 ALTER ... COMMENT
mysql -uroot -p < 60-user-pay-password.sql      # 5.13 用户域改造（user 加 pay_password，拆分登录/支付口令）
                                                #      ⚠️ 必须在**发布应用之前**执行：应用启动即依赖该列
# 5.14 存量弱口令批量重置（61 号）**默认不执行**：它会改变用户登录凭据，
#      需先跑脚本第 1 段的清点语句确认数量，再手动放开第 2 段注释执行

# ── 6) 演示数据（**仅演示库，勿在生产执行**：50/51 内含按 id 区间的 DELETE） ──
mysql -uroot -p < 50-seed-local-life-data.sql   # 6.1  门店套餐 + 精选实物 + 评价 + 演示订单
mysql -uroot -p < 51-seed-more-data.sql         # 6.2  补齐 8 个缺失类型的门店 + 各域空表铺数据
mysql -uroot -p < 50-seed-local-life-data.sql   # 6.3  ★再跑一遍 50：为 51 新增的门店生成套餐与评价
mysql -uroot -p < 51-seed-more-data.sql         # 6.4  ★再跑一遍 51：补齐依赖"评价"的聚合数据（评论/点赞/Feed）
mysql -uroot -p < 52-fix-orphan-pay-orders.sql  # 6.5  清理前端 Number() 精度丢失产生的孤儿支付单并退款
mysql -uroot -p < 63-user-demo-pay-password.sql # 6.6  ★演示 / 冒烟环境专用：Jack 支付口令预置 888888、登录口令预置 123456
#      ⚠️ 必须在 60 号之后执行（依赖 pay_password 列）；⛔ 生产库与任何对外开放的库都不得执行
#      ⚠️ 52a 是 52 之前的只读清点，不改数据，位置见「已知偏差台账」；本清单不含 52a 的调用
# 跑完调一次接口：POST /search/view/rebuild 与 POST /shops/cache/geo/rebuild

# ── 7) 可选：从原点评库迁移门店数据（需要外部库 上游单体工程 已存在） ──
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS 上游单体工程 DEFAULT CHARSET utf8mb4;"
# 上游单体工程 的建表数据在 上游单体工程 项目里（不在本仓库内）
mysql -uroot -p < 99-migrate-source-data.sql
```

### 2.1 关于顺序的三点说明

1. **`50 → 51 → 50 → 51` 是有意重复的**：`51` 会新增 8 类、16 家门店（id 101~116），
   而门店套餐与探店评价由 `50` 按 `shop` 表全量生成 —— 所以 `51` 加完门店后必须再跑一次 `50`，
   再跑一次 `51` 去补那些依赖"评价"的聚合数据（评论 / 点赞 / Feed 收件箱）。
   这正是 `docs/internal/最终验收与交付清单.md` **7.7 节**写明的四步顺序。
2. **`23` 排在 `30` 后面（破数字序）**：`23-content-image-default.sql` 改的是 `llmall-content.blog`，
   而这张表由 `30-content.sql` 的 `CREATE TABLE IF NOT EXISTS` 建立 —— 按数字序把 `23` 放在 `30` 前面，
   在全新库上会直接报 `Table 'llmall-content.blog' doesn't exist`。这是本次审计发现的一处**顺序缺陷**。
3. **`99` 放最后**：它把 `上游单体工程` 的门店 upsert 进 `llmall-shop.shop`/`shop_type`，
   必须等 `10-shop.sql` 建好表、`50/51` 的演示门店就位之后再跑（`ON DUPLICATE KEY UPDATE`，可重复执行）。

### 2.2 与 `docs/internal/最终验收与交付清单.md` 的核对（reconciliation）

- 7.7 节写的是**演示数据四步**：`50 → 51 → 50 → 51`。**本 README 已采纳该顺序**（第 6 步 6.1~6.4）。
- 7.7 节之外，文档只笼统写了"按 `sql/merged/README.md` 顺序导入"（见该文档第 52、128 行），
  没有给出完整清单；本 README 的第二节即为该完整清单，**以本文件为准**。
- 本 README 的旧版本把演示数据写成 `50 → 51 → 52`（漏了"再跑一遍 50/51"），与 7.7 不一致；
  现已按 7.7 修正，另修正了 `23` / `30` 的数字序颠倒。**如有冲突，一律以本文件为准。**

---

## 二·补、2026-10 幂等性审计：改了哪些东西

| # | 缺陷 | 现象 | 修复 |
|---|---|---|---|
| 1 | `11-item-alter.sql` 语法错 | 语句写成 `ALTER TABLE item` + 表名后多一个字母 `r`，**永远执行不了**，`idx_type_status` / `idx_shop_sold` 两个索引从未被脚本建出来 | 按上下文与线上库实际索引形态还原原意并修正 |
| 2 | `23` / `30` 执行顺序颠倒 | 按数字序 `23` 跑在 `30` 前面，全新库上 `llmall-content.blog` 还不存在 → `ERROR 1146` | README 把 `23` 排到 `30` 之后（脚本内也加了守卫：表不存在时**照旧报错**、不静默跳过） |
| 3 | `51-seed-more-data.sql` DELETE 区间少算 1 | `DELETE ... user_sign WHERE id BETWEEN 990001 AND 999999`，而 INSERT 会生成 `id = 990000` → **第二遍执行**必然 `ERROR 1062 Duplicate entry '990000'` | 区间改为 `990000 AND 999999` |
| 4 | `03` / `03b` 缺 `USE` | 脚本本身没有 `USE seata;`，而 README 给的命令是 `mysql -uroot -p < 03-....sql`（不带库名）→ `ERROR 1046 No database selected` | 两个脚本各补一句 `USE` + 反引号 `seata` |
| 5 | 8 条裸 `ALTER TABLE`（`11/21/22/23/31/41`） | 第二遍执行报 `Duplicate column name` / `Duplicate key name` | 全部改成第三节的 `information_schema` 守卫写法 |

验收方式：把上面第二节的清单**连续跑两遍**，第二遍 `ERROR` 行数必须为 0，
且 `information_schema` 的列数 / 索引行数 / 每表结构哈希与执行前**逐字节一致**。
（2026-10-13 已在 <vm-ip> 的 MySQL 8 上实测：PASS2 / PASS3 / PASS4 连续三遍 `ERROR` 均为 0，
`llmall-%` 共 40 张表的结构哈希全部不变。）

---

## 三、脚本清单与幂等性（file-by-file）

每个语句要么本身就是幂等的，要么已改成"先查 `information_schema` 再决定执行/跳过"的守卫写法。

| # | 脚本 | 幂等手段 |
|---|---|---|
| 00 | `00-create-databases.sql` | `CREATE DATABASE IF NOT EXISTS` |
| 01 | `01-undo-log-all.sql` | `CREATE TABLE IF NOT EXISTS`（8 个库各一张 undo_log） |
| 02 | `02-shared-infra.sql` | `CREATE TABLE IF NOT EXISTS`（message_log + 5 份 consume_record） |
| 03 | `03-seata-server.sql` | `CREATE TABLE IF NOT EXISTS` + 种子用 `INSERT IGNORE` |
| 03b | `03b-seata-server-1.5.2-upgrade.sql` | **house style 出处**：`@c` + `IF()` + `PREPARE/EXECUTE/DEALLOCATE`；种子用 `INSERT IGNORE`；`CONVERT TO CHARACTER SET` 本身可重复执行 |
| 10 | `10-shop.sql` | `CREATE TABLE IF NOT EXISTS` ×3 |
| 11 | `11-item-alter.sql` | **新增守卫**：`COLUMNS`×1 + `STATISTICS`×2（并修掉 "ALTER TABLE item 表名后多一个 r" 的语法错） |
| 55 | `55-seed-core-shops.sql` | **演示数据（核心门店）**：`INSERT IGNORE` 14 家核心门店（id 1~14）与类型 1 / 2。**必须排在 50 之前**（50 号按 shop 表全量生成套餐）。用 `INSERT IGNORE` 而不是 `ON DUPLICATE KEY UPDATE`：已存在的行整行跳过，不会把长期演示库里跑出来的售量/评价数/热度打回种子值。**⚠️ 仅演示库** |
| 20 | `20-promotion.sql` | `CREATE TABLE IF NOT EXISTS` ×3 |
| 21 | `21-user-alter.sql` | **新增守卫**：`COLUMNS`×1（nick_name/icon）+ `STATISTICS`×1（uk_phone） |
| 04 | `04-balance-flow.sql` | `CREATE TABLE IF NOT EXISTS`（余额变动流水 + `uk_biz(biz_type,biz_no)` 幂等键；B2 资金一致性，脚本号小于 10 但依赖 21 建好的 user 表，故排在其后） |
| 05 | `05-shop-message-log.sql` | `CREATE TABLE IF NOT EXISTS`（门店域本地消息表 `llmall-shop.message_log`，与 `llmall-trade.message_log` 同构；B4 评价→热度→搜索的可靠投递） |
| 06 | `06-content-message-log.sql` | `CREATE TABLE IF NOT EXISTS`（内容域本地消息表 `llmall-content.message_log`，与 05 逐列一致；B4 `review.created` 生产端可靠投递） |
| 22 | `22-user-time-default.sql` | **新增守卫**：`COLUMNS` 判断两列是否已是目标形态（`datetime/NOT NULL/DEFAULT CURRENT_TIMESTAMP[ ON UPDATE ...]`） |
| 23 | `23-content-image-default.sql` | **新增守卫**：`TABLES`（表不存在要报错，不静默跳过）+ `COLUMNS` 判断 `images` 是否已是 `varchar(2048) NULL DEFAULT ''` |
| 30 | `30-content.sql` | `CREATE TABLE IF NOT EXISTS` ×5 |
| 31 | `31-order-alter.sql` | **新增守卫**：`COLUMNS`×1（5 列）+ `STATISTICS`×1（3 索引） |
| 32 | `32-shop-staff.sql` | 核销判权落库：`CREATE TABLE IF NOT EXISTS`（`shop_staff` + `uk_shop_user(shop_id,user_id)`）+ 演示种子 `INSERT IGNORE ... SELECT`（先按 username 查 `llmall-user.user.id`，Jack 不是写死的 1）。**⚠️ 必须早于/同批于应用发布**：未建表时核销 fail-closed 全部拒绝。**⚠️ 种子仅演示库** |
| 40 | `40-search.sql` | `CREATE TABLE IF NOT EXISTS` ×3 |
| 41 | `41-search-geo.sql` | **新增守卫**：`COLUMNS`×1（x/y） |
| 50 | `50-seed-local-life-data.sql` | 演示数据：`DELETE`（id 区间）+ 重插；`UPDATE` 幂等收敛；`INSERT` 多带 `NOT EXISTS` 守卫。**⚠️ 会删数据，仅演示库** |
| 51 | `51-seed-more-data.sql` | 演示数据：7 处 `DELETE`（id 区间）+ 重插；`INSERT IGNORE` / `NOT EXISTS`。**⚠️ 会删数据，仅演示库** |
| 52 | `52-fix-orphan-pay-orders.sql` | 纯 `UPDATE`（孤儿单退一次款后即无孤儿，重复执行为空操作） |
| 60 | `60-user-pay-password.sql` | **新增守卫**：`COLUMNS` 判断 `pay_password` 是否已存在。`ADD COLUMN ... NOT NULL DEFAULT '123456'` 会把**既有行**一并填充，故存量账号余额支付行为不变。**⚠️ 必须早于应用发布** |
| 61 | `61-user-weak-password-reset.sql` | 存量弱口令重置：第 1 段清点、第 2 段 `UPDATE`（**默认注释，需人工启用**）、第 3 段校验。`WHERE password = '<初始哈希>'` 保证幂等且不误伤已改密账号 |
| 62 | `62-align-dict-comments.sql` | **存量库注释对齐（全新初始化可跳过）**：7 条纯 `ALTER TABLE ... MODIFY COLUMN ... COMMENT`，把 `stock_flow` / `voucher` / `user_voucher` / `pay_order`(×2) / `order` / `message_log` 的字典型注释改成与代码事实一致。**只改 COMMENT，不动类型/可空性/默认值/数据；可重复执行**。源文件已同步为同一口径，故新库无需执行 |
| 63 | `63-user-demo-pay-password.sql` | **演示 / 冒烟环境专用**：把演示账号 Jack 的两个口令列收敛成"冒烟能直接用"的状态 —— `pay_password` 从 60 号写入的初始口令哈希改成 888888 的哈希（否则被"首次支付前强制改密"拦下，见 `UserServiceImpl#deductMoney`）；`password` 从原商城导出的那条哈希改成 123456 的哈希（否则 `MallE2E` 第一步登录就红）。幂等：两段 `UPDATE` 都只命中"仍是已知旧值"的行，第二遍命中 0 行；已自行改过口令的账号不受影响。列不存在时只打印提示、不报错。**⛔ 生产库不得执行** |
| 99 | `99-migrate-source-data.sql` | `INSERT ... ON DUPLICATE KEY UPDATE` + `UPDATE ... WHERE hot_score = 0` |

### 3.1 守卫模板（统一写法）

```sql
-- 列：查 COLUMNS；索引：查 STATISTICS
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item'
             AND COLUMN_NAME IN ('shop_id', 'type'));
SET @s := IF(@c = 0,
    'ALTER TABLE `item` ADD COLUMN ...',           -- 不存在 → 执行原语句
    'SELECT ''...已存在，跳过''');                  -- 已存在   → 只打印一句
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
```

- 用的是 `DATABASE()`（依赖脚本里的 `USE 库名;`）；`22` / `23` 因为原语句就是全限定表名，仍写死库名。
- **粒度和原文件一致**：原来一条 `ALTER TABLE` 加几列/几个索引，就还是那一条语句，
  只是外面套了守卫 —— 所以全新库上的效果与改造前逐字一致。
- 一条 `ALTER` 里的多个对象用"**全都不存在才执行**"判断（`@c = 0`）：
  正常路径（全新库 or 已全部就位）行为完全正确；只有"跑了一半被打断"的中间态才会报
  `Duplicate column name` —— 与改造前的行为一致，不会更糟，且不会静默漏改。

---

## 四、注意事项

1. **库名口径**：订单库就叫 `llmall-trade`（与 `llmall-trade-service/.../application.yaml` 的
   `llmall.db.database` 一致），不存在 `llmall-order`。文档里出现的 `llmall-order` 只是命名建议，
   相关示例 SQL 请自行替换库名。
2. **数据迁移语句默认是注释状态**（每个脚本末尾的 `INSERT INTO ... SELECT`）。先看一遍再取消注释 ——
   尤其是 `21-user-alter.sql` 里的用户迁移，密码策略要先定：**统一 BCrypt，历史数据重置密码**，
   不要留两套校验分支。
3. **`ALTER TABLE user ADD UNIQUE KEY uk_phone`** 可能因历史数据里 phone **重复**而失败
   （MySQL 唯一索引允许多个 NULL，所以 NULL 多不会失败 —— 只有重复的非 NULL 值才会）：
   先 `SELECT phone, COUNT(*) FROM user GROUP BY phone HAVING COUNT(*) > 1;` 清洗后再加。
   这是**数据**问题，不是幂等问题（索引已存在时守卫会跳过，不会重复报错）。
4. **MySQL 8 兼容性**：本目录脚本已去掉原点评脚本里的 `ZEROFILL` 和 `'0000-00-00'` 默认值
   （严格模式下会直接报错）。
5. **幂等性（2026-10 改造后）**：本目录**所有脚本都可以重复执行**。
   原来那句"`ALTER` 重复执行会报 Duplicate column/duplicate key，属正常现象，忽略即可"**已作废** ——
   现在 `11/21/22/23/31/41` 的 `ALTER` 都带 `information_schema` 守卫，
   第二遍执行只会打印 `... 已存在，跳过`，不会有任何 `ERROR`，也不会改表结构。
6. **`50` / `51` 会删数据**：两个种子脚本用"按 id 区间 DELETE 再重插"保证可重复执行，
   文件头都有 `⚠️ 仅演示库，勿在生产执行` 的警示块。生产库只跑 `00~41`，跳过 `50/51/52/99`。
7. **`32` 是核销权限的落库脚本，必须与应用同批上线**：`32-shop-staff.sql` 建的 `shop_staff`
   是"谁能核销"的唯一事实来源。应用先上线、脚本没跑 → 核销接口查不到员工关系，按 fail-closed
   全部拒绝（不会退化成"谁都能核销"，但员工也用不了）。种子里 Jack 被授予 1 号与 10 号店的
   STAFF，仅演示库需要；生产库只执行建表段、不执行种子段（把种子段整段注释掉，或按实际员工
   关系自行 `INSERT`）。
8. **改表结构前先在工作副本验证**：`sql/merged/` 是"从零重建"的脚本；对已经在跑的库做变更，
   请单独写增量脚本并先备份。
9. **跑完演示数据记得调接口**：`POST /search/view/rebuild`（重建搜索读模型）与
   `POST /shops/cache/geo/rebuild`（重建门店地理缓存）。
