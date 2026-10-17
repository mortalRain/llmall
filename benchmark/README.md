# benchmark · 压测计划与结论（2026-10-17）

> 目的不是刷 QPS 数字，而是给台账里的三个靶子补数据：
> **C-01 Feed 读路径**、**C-02 ES 空索引不回落**、**F-06/F-07 写路径禁重试与幂等**。
> 压不出问题就如实写"未复现"并给出触发条件 —— 本文件里每一条结论都能对着 `.jtl` 原始结果复核。

---

## 一、目录内容

| 文件 | 压什么 | 对应靶子 |
|---|---|---|
| `read-c01-feed.jmx` | `GET /follows/feed?size=10`（Feed 收件箱读）+ `GET /items/page`（商品列表读，读放大对照） | C-01 |
| `read-c02-search.jmx` | `GET /search?keyword=火锅` | C-02 |
| `write-order-pay.jmx` | `POST /orders` → `POST /pay-orders` → `POST /pay-orders/{id}` | F-06 / F-07 |

`jmeter -n -t <上面的 jmx> ...` 无头执行；结果落在 `logs/benchmark/`（不入库）。

---

## 二、环境规格（**QPS 只有配上这张表才有意义**）

| 项 | 值 |
|---|---|
| 宿主 CPU | Intel Core i7-14650HX，16 物理核 / 24 逻辑核，基频 2.2 GHz |
| 宿主内存 | 15.8 GB（压测期间已用 71%~98%） |
| 磁盘 | C: 108 GB 可用（红线要求 >20GB 才开压） |
| 被测形态 | `demo.ps1` 起的 **Docker 容器**：gateway + 各域服务，每服务 `mem_limit 256m` / `-Xmx192m`；MySQL 容器（宿主 3307）、Redis+3 哨兵容器、Nacos 容器、RabbitMQ 容器 |
| ES | **容器版** `elasticsearch:7.17.10`（宿主端口 9201，挂 IK 7.17.10）；**没有**使用宿主原生 ES，也没给它发过压测流量 |
| 数据 | 门店 30 家、到店套餐 150 个、评价 3043 条（seed 链原样） |
| JMeter | 5.6.3（解压到 `D:\develop\apache-jmeter-5.6.3`，未改 PATH/注册表/服务），堆 `-Xmx512m` |
| **CPU 共享关系（关键）** | **JMeter 与被测容器跑在同一台宿主上，抢同一批物理核**。所以下表是"单机同构对比"的口径：绝对值不能与生产对标，但**同一环境下的梯度变化**（10→50→100 并发）是可信的 |
| 隔离性 | 非隔离环境：宿主同时跑着浏览器、IDE、一台 4GB 的 VMware 虚拟机；压测期间未跑其它构建任务 |

### 资源红线（全程生效）

- 并发封顶 **100**；JMeter 堆 **-Xmx512m**；每 **5 秒**采样宿主 CPU/内存/C 盘剩余，写进 `logs/benchmark/*.host-samples.csv`；
- 熔断条件：CPU 持续 >90% 达 30 秒 / 内存 >90% / C 盘剩余 <5GB → 立即停 JMeter 并标记 `Aborted`。
  本轮**没有触发熔断**（单点峰值到过 100%，但从未连续 30 秒 >90%，见下表 PeakCpu 列）；
- 只压容器；写路径只写容器库（`127.0.0.1:3307`），**绝不写 VM 演示库 192.168.189.128**；
- 写压测前用 `scripts/SmokeRollback.java capture` 记水位线，压完 `rollback` 复原并复核。

---

## 三、结果

三档并发（10 / 50 / 100），每档 60 秒，档间冷却 30 秒。RPS 为 JMeter 聚合吞吐（含全部采样器）。

### C-01 · Feed 读路径

| 采样器 | 并发 | 请求数 | 错误率 | Avg | P95 | P99 | 宿主峰值 CPU |
|---|---|---|---|---|---|---|---|
| `GET /follows/feed` | 10 | 130,315 | 0% | 4.3 ms | 6 ms | 7 ms | 65% |
| `GET /follows/feed` | 50 | 142,526 | 0% | 20.1 ms | 39 ms | 48 ms | 91% |
| `GET /follows/feed` | 100 | 141,792 | 0% | 40.5 ms | 70 ms | 84 ms | 94% |
| `GET /items/page` | 10 | 181,165 | **93.49%** | 3.0 ms | 6 ms | 9 ms | — |
| `GET /items/page` | 50 | 322,659 | **96.39%** | 8.8 ms | 16 ms | 35 ms | — |
| `GET /items/page` | 100 | 305,186 | **96.21%** | 18.7 ms | 34 ms | 92 ms | — |

> `GET /items/page` 的错误是 **Sentinel 限流**（`itemPage` QPS=200 的最小规则集），不是服务故障 ——
> 10 并发就已经打到 3000+ QPS，超出部分被规则拒掉。这本身是一条结论：**该接口的容量上限由限流规则定死在 200 QPS**。

### C-02 · /search（ES 检索）

| 索引状态 | 并发 | 请求数 | 错误率 | Avg | P95 | P99 | RPS |
|---|---|---|---|---|---|---|---|
| 已灌数（30 docs） | 10 | 123,271 | 0% | 4.5 ms | 7 ms | 8 ms | 2057 |
| 已灌数（30 docs） | 50 | 230,922 | 0% | 12.3 ms | 20 ms | 25 ms | 3853 |
| 已灌数（30 docs） | 100 | 219,893 | 0% | 26.0 ms | 37 ms | 47 ms | 3669 |
| **空索引（0 docs）** | 10 | 133,919 | 0% | 4.1 ms | 6 ms | 7 ms | 2234 |
| **空索引（0 docs）** | 50 | 255,935 | 0% | 11.1 ms | 18 ms | 22 ms | 4271 |
| **空索引（0 docs）** | 100 | 264,558 | 0% | 21.5 ms | 35 ms | 44 ms | 4414 |

### 写路径（下单 → 支付，50 并发 × 60 秒）

| 采样器 | 请求数 | 错误率 | Avg | P95 | P99 |
|---|---|---|---|---|---|
| `POST /orders` | 1,403 | 0% | 72.3 ms | 70 ms | 950 ms |
| `POST /pay-orders` | 1,399 | 0% | **1156 ms** | 1612 ms | **2332 ms** |
| `POST /pay-orders/{id}` | 1,371 | 0% | 859 ms | 1340 ms | 2939 ms |
| 整链路吞吐 | — | — | — | — | **≈68.6 req/s（≈23 单/秒）** |

---

## 四、结论（逐条对应台账）

### C-01 · Feed 读路径：**N+1 未复现**

用 MySQL `Com_select` 增量除以请求数直接量"每次请求几条 SQL"，这是 N+1 的硬判据（延迟随并发上升也可能只是 CPU 饱和，不能当证据）：

| 探针 | 请求数 | Com_select 增量 | 每请求 SQL |
|---|---|---|---|
| 空闲 10 秒（背景） | — | 2 | — |
| `GET /follows/feed` 串行 | 100 | 101 | **≈1.01** |
| `GET /follows/feed` 10 路并发 | 200 | 209 | **≈1.04** |
| `GET /items/page` pageSize=1 | 50 | 101 | **≈2.02** |
| `GET /items/page` pageSize=10 | 50 | 101 | **≈2.02** |
| `GET /items/page` pageSize=30 | 50 | 101 | **≈2.02** |

**结论**：`/items/page` 的 SQL 条数与每页条数（1 / 10 / 30）**完全无关**，`/feed` 在并发下也仍是 1 条 ——
**读路径没有 N+1**，并发下也没有放大。台账 `docs/已知偏差台账-2026-10-15.md` L55（C-01）描述的是
**读路径没有"拉取合并"分支**（纯推模式，超阈值作者的笔记对粉丝不可见），那是**功能缺口**，不是性能缺口；
本次压测证明的是"现有纯推读路径很便宜（1 SQL/请求，P99 < 90ms @100 并发）"，给未来补拉模式留了对照基线。

> 触发条件（供台账引用）：若将来把 C-01 补成"读时拉取合并"，N+1 风险点会出现在
> "关注作者集合 × 每人近 N 条笔记"上 —— 届时应按关注数做批量查（`IN`）+ 内存归并，
> 复测口径仍是本文件第四节的 `Com_select ÷ 请求数`。

### C-02 · ES 空索引：**缺口已复现（且找到了精确触发条件）**

同一台容器 ES、同一个关键词 `火锅`：

| 索引状态 | `/search/engine` | `/search` 返回 |
|---|---|---|
| 已灌 30 条 | `esDocs:30, consistent:true` | `total=2`（正常结果） |
| **索引被删（不存在）** | `esDocs:-1, consistent:false, note:"ES 不可用: Index [shop_search] not found"` | **`total=2`**（回落 MySQL 读模型，日志有 `ES 检索失败，回落 MySQL 读模型`） |
| **索引存在但为空（0 条）** | `esDocs:0, consistent:false, note:"不一致（读模型 30 vs 索引 0）：调 POST /search/index/rebuild 重建"` | **`total=0, list=[]`（HTTP 200，静默空结果，不回落、不打 WARN）** |

**结论**：台账猜测的"ES 对索引不存在返回 200+0 条"这一半**不成立** —— 索引不存在会抛
`index_not_found_exception`，`engine=auto` 正常回落 MySQL。但**另一半成立且更隐蔽**：
**索引建出来了却没有数据时**，ES 返回 `200 + 0 命中`（不抛异常），于是检索**静默返回空列表**，
既不回落 MySQL，也不打 WARN；只有 `/search/engine` 能看出 `esDocs:0 / consistent:false`。

这条与 `docs/能力缺口清单-2026-10-15.md` 第 3 条的原文（"本条结论尚未实机验证"）对应 —— 现在已验证，
且给出了**比原文更精确的触发条件**：不是"索引不存在"，而是"**索引存在但为空**"。

> 触发条件：新环境首次部署后 `POST /search/view/rebuild` 建好读模型但漏了 `POST /search/index/rebuild`；
> 或索引被误删后只重建了空索引。届时搜索页"什么都搜不到"，而 `esDocs:0` 是唯一的线索。

### F-06 / F-07 · 写路径：**50 并发下零重复扣款、并发双击只扣一次**

50 并发 × 60 秒、共 1,403 次完整下单→支付循环之后的账实核对：

| 核对项 | 值 | 判据 |
|---|---|---|
| 创建订单 | 1,403 条 | — |
| 申请支付单 | 1,399 条，`COUNT(DISTINCT biz_order_no)=1399` | **一单一张支付单**（幂等键生效） |
| 支付成功 | 1,371 次 | — |
| 余额变化 | 100,000,000 → 64,902,400 = **35,097,600 分** | 35,097,600 ÷ 25,600 = **1371.0** → **恰好等于成功次数，无重复扣款** |
| `balance_flow` 新增 | 1,371 条 | 与扣款次数一一对应 |
| 库存/销量 | stock 100,000→98,597（-1403）、sold 253→1,656（+1403） | 与创建订单数一致 |

**并发双击（F-07 正面实验）**：同一张支付单，20 个线程同时 `POST /pay-orders/{id}`：

```
OK|200 ×1        ← 只有一次真正扣款
ERR|400 ×19      ← 其余全部被拒（交易已支付或关闭）
余额 64,902,400 → 64,876,800，差额 25,600 = 恰好一次扣款
```

> F-06（Feign/OkHttp 禁重试）的结论不变：本轮是在"重试可能发生"的真实压力下复验，
> 结果是**没有出现重复扣款**；结合 F-07 的幂等键，即使真发生重发也会被挡在业务幂等那一层。

---

## 五、顺手挖到的两条（不在台账里，但属于"压出来的发现"）

1. **`POST /pay-orders` 是整条写链路里最慢的一步**：Avg 1156 ms、P99 2332 ms，比创建订单（72 ms）慢一个数量级。
   整链路 68.6 req/s ≈ 23 单/秒，瓶颈在这里，不在下单。
2. **`itemPage` 的 Sentinel 规则把商品列表钉死在 200 QPS**：10 并发就已达 3000+ QPS 尝试量，
   超出即 400。这是**设计内**行为（不是故障），但它意味着"商品列表的容量上限 = 限流阈值"，
   调容量时应先看这条规则而不是先加机器。

---

## 六、复现方式（无头）

```powershell
# 0) 起环境（容器版，ES 也是容器；数据卷复用即可）
$env:JAVA_HOME='D:\develop\jdk11'; $env:MYSQL_HOST_PORT='3307'
pwsh -File demo.ps1 -SkipBuild -ExternalEs -EsUrl http://127.0.0.1:9201 -SkipSql -SkipSmoke

# 1) 读路径（并发梯度 10/50/100，每档 60s，档间冷却 30s）
$env:HEAP='-Xms256m -Xmx512m'
& D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t benchmark\read-c01-feed.jmx `
    -Jthreads=10 -Jduration=60 -Jramp=5 -Jtoken=<登录令牌> `
    -l logs\benchmark\c01-t10.jtl -Jjmeter.save.saveservice.output_format=csv
# C-02 另需在两种索引状态下各跑一遍：
#   curl -X DELETE http://127.0.0.1:9201/shop_search      # 空索引（复现静默空结果）
#   或先用 gates/EsBulkLoad.java 灌 30 条               # 有索引

# 2) 写路径（先 capture 水位线，压完 rollback）
java -cp <mysql-connector.jar> scripts\SmokeRollback.java capture 127.0.0.1:3307 root 123 logs\benchmark\watermark.json
& D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat -n -t benchmark\write-order-pay.jmx -Jthreads=50 -Jduration=60 `
    -Jtoken=<登录令牌> -JaddressId=<地址 id> -l logs\benchmark\write-t50.jtl
java -cp <mysql-connector.jar> scripts\SmokeRollback.java rollback 127.0.0.1:3307 root 123 logs\benchmark\watermark.json
```

`-Jtoken` / `-JaddressId` 由外层脚本登录后取（本轮用一个本地守卫脚本 `logs/benchmark/bench-guard.ps1` 负责：
每 5 秒采样宿主 CPU/内存/磁盘 → 熔断 → 跑 JMeter → 从 `.jtl` 算 P95/P99/错误率）。
令牌也可以像 `write-order-pay.jmx` 的 setUp 那样在计划内取（脚本属性名必须是 `script` / `scriptLanguage`，
写成 `JSR223PostProcessor.script` 会被 JMeter 静默忽略 —— 仓库里旧的那份 `locallife-benchmark.jmx` 就踩了这个坑）。

---

## 七、局限（如实写）

- **JMeter 与被测服务同机**：QPS 绝对值不可与生产对标，只有同环境的梯度对比有意义；
- **非隔离环境**：宿主同时跑浏览器/IDE/一台 VM；三次 100 并发档的宿主峰值 CPU 到过 94%~100%（未连续 30 秒 >90%）；
- **为腾内存临时停过部分服务**：读路径阶段停过 shop/cart/trade/pay/promotion，写路径阶段停过 search/content/bench-es；
  因此**没有在同一时刻压全部 10 个服务**；
- **写压测把 `stock` 与 `balance` 临时抬高过**（否则 60 秒内会被库存/余额提前卡住），压完由 `SmokeRollback` 按水位线复原并复核通过；
- **门店详情（`GET /shops/{id}` 的缓存读）本轮未采集数据**：环境回收前没跑这一档，
  `README` 里关于它的量化措辞已改为不含未实测数字。
