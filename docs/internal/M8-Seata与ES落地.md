# M8 施工单：Seata + Elasticsearch 落地（进度板）

> 这是操作单，不是给人看的汇报。拓扑：**Seata 跑在 VM 的 Docker**（占 VM 512M），
> **ES 跑在 Windows 宿主**（占宿主 ~1.2G，服务在 IDEA 里直连 localhost:9200）。

## 一、当前状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| Seata · 服务端配置 | ✅ 已完成 | `docker/seata/registry.conf` + `file.conf`（此前**文件不存在**，compose 挂载了空路径，这是起不来的直接原因） |
| Seata · 业务侧接线 | ✅ 已完成 | 3 个 pom 加依赖（trade=TM，item/promotion=RM）+ 3 个 `application.yaml` 加 seata 配置 + `OrderServiceImpl#createOrder` 加 `@GlobalTransactional`，**默认 `seata.enabled=false`（注解惰性，项目照常跑）** |
| Seata · 服务端启动 | ⏳ 等你在 VM 上执行 | 拉镜像 → 建 seata 三张表 → `up -d seata`（见第 3 节） |
| Seata · 验证与对照 | ⏳ 未开始 | 需服务端就绪 |
| ES · 服务端 | 🔄 下载中 | ES 7.17.10（Windows zip）+ IK 7.17.10，放 `D:\develop\` |
| ES · 业务侧接线 | ✅ 已完成 | 5 个新类 + 3 个文件改动，见第 4 节 |
| ES · 启动与验证 | ⏳ 未开始 | 下载完我解压、配堆、启动、验收 |

## 二、两个版本决定（踩过才知道，别再改回去）

1. **ES 用 7.17.10，不是 7.17.3**：`spring-boot-dependencies:2.7.12` 里
   `<elasticsearch.version>7.17.10</elasticsearch.version>`，即客户端默认就是 7.17.10。
   **客户端比服务端新会被 ES 拒绝握手**，所以服务端、IK 分词器都对齐 7.17.10。
   （文档里原先写的 7.17.3 已批量改为 7.17.10。）
2. **Seata 用 1.4.2**：与 Spring Cloud Alibaba 2021.0.4.0 的客户端对齐（沿用设计文档）。

## 三、Seata 待你执行（VM）

```bash
free -m && docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}'
docker pull seataio/seata-server:1.4.2
docker run --rm --entrypoint sh seataio/seata-server:1.4.2 -c 'find / -name "mysql.sql" 2>/dev/null'
docker run --rm --entrypoint sh seataio/seata-server:1.4.2 -c 'cat <上一步的路径>' > /tmp/seata.sql
docker exec -i mysql mysql -uroot -p123 seata < /tmp/seata.sql
docker exec -i mysql mysql -uroot -p123 -e "show tables from seata;"      # 期望 3 张表
```

然后把 `docker-compose-infra-lite.yml` 与 `docker/seata/` 传到 VM 的 `/root/llmall/`，
`docker compose -f docker-compose-infra-lite.yml --profile full up -d seata`，
验收：`docker ps` Up + Nacos 服务列表出现 `seata-server` + `http://192.168.189.128:7091` 可开。

## 四、ES 已完成代码（llmall-search-service）

| 文件 | 作用 |
|---|---|
| `es/ShopSearchDocument.java` | 索引文档：IK 分词（ik_max_word / ik_smart）、geo_point、function_score 用到的 hotScore/score/comments/eventTime |
| `es/EsShopSearchGateway.java` / `Impl` | ES 收口层：检索（multiMatch + function_score + geo 排序）、单/批量写、全量重建、count/healthy |
| `es/ShopSearchEngineProperties.java` | `llmall.search.engine = es / mysql / auto`（默认 auto）+ 打分参数 |
| `es/ShopSearchIndexInitializer.java` | 启动建索引，**失败只 warn 不影响启动** |
| `domain/vo/SearchEngineStatusVO.java` | 对账视图（读模型条数 vs 索引文档数） |
| `service/impl/SearchServiceImpl.java` | ES 分支 + 回落 + 事件同步（读完整行再写 ES）+ 读模型重建后重灌索引 + 对账 |
| `controller/SearchController.java` | 新增 `GET /search/nearby`、`POST /search/index/rebuild`、`GET /search/engine` |
| `domain/po/ShopSearchDoc.java` + `sql/merged/41-search-geo.sql` | 读模型补 x/y（geo_point 用） |
| `pom.xml` | 打开 `spring-boot-starter-data-elasticsearch`（7.17.10） |
| `application.yaml` / `application-local.yaml` | `llmall.search.engine: auto` + 打分参数；`llmall.es.host: localhost` |

**引擎语义**（也是"ES vs MySQL"对照压测的开关）：
- `auto`：优先 ES，抛错回落 MySQL 读模型（生产形态）；
- `es`：只用 ES，不回落（压测形态，避免把回落耗时混进 ES 数据）；
- `mysql`：回到引入 ES 之前的行为。

**功能增强点**（不只是"换个引擎"）：
- IK 分词 + `BEST_FIELDS` 多字段相关性（原来 MySQL 版是 4 个 `like` 的 OR）；
- `function_score = SUM(hotScore LOG1P × 1.0, gauss(eventTime, now, 30d), score × 0.5, comments LOG1P × 0.3)`
  —— 热度取对数防止头部霸榜，时间衰减给新近被评价的门店加权；
- `GET /search/nearby?x=&y=`：geo_distance 升序 + 回填 `distance`（原来只能在 SQL 里硬算）。

**已知取舍（写在代码注释里了）**：
- ES 写入是**整文档替换**：热点事件只带 3 个字段，所以同步前必须重读完整行，
  否则名称/地址/坐标会被刷成 null（这是接 ES 最容易犯的错，代码里专门写了注释）；
- ES 同步是 **best-effort**：失败只告警不抛错（权威数据在 MySQL），
  靠 `POST /search/index/rebuild` 与 `GET /search/engine` 对账补齐；
- MySQL 回落分支**不支持按距离排序**（distance 为空）—— 有意为之，不因 ES 挂了就让接口 500。

## 五、验证方法（我这边没有 Maven，所以先"抄答案对标准答案"）

改动涉及 ES 客户端 API，本地无法编译，所以先下载真实 jar 并用 `javap` 核对签名：
`spring-data-elasticsearch 4.4.12` + `elasticsearch-rest-high-level-client 7.17.10` + `elasticsearch 7.17.10`。

核对结果（都通过）：
- `ElasticsearchOperations`：`save(T)` / `save(Iterable)` / `count(Query, Class)` / `search(Query, Class)` / `indexOps(Class)`；
- `IndexOperations`：`exists()` / `create()` / `createMapping()` / `putMapping(Document)` / `refresh()`；
- `NativeSearchQueryBuilder`：`withQuery` / `withSort` / `withTrackTotalHits` /（父类）`withPageable`；
- `FunctionScoreQueryBuilder`：`FunctionScoreQueryBuilder(QueryBuilder, FilterFunctionBuilder[])` / `scoreMode` / `boostMode`；
- `ScoreFunctionBuilders.fieldValueFactorFunction(...).factor(float).modifier(Modifier.LOG1P).missing(double)`；
- `ScoreFunctionBuilders.gaussDecayFunction(String, Object, Object)`；`FieldValueFactorFunction.Modifier.LOG1P`；
- `SortBuilders.geoDistanceSort(String, double, double)`（**参数顺序是 lat, lon** → 传 y, x）/ `scoreSort()` / `fieldSort()`；
- **`@Field` 在 4.4 没有 `fields` 属性**（多字段是 5.x 的能力）→ 已改掉，别再写 `fields = @Field(...)`。

配置类改动用 snakeyaml 1.30 实际解析校验（`D:\dsh\.verify\YamlCheck.java`，43 项断言全过）。

## 六、内存纪律

| 位置 | 组件 | 占用 | 何时起 |
|---|---|---|---|
| VM | 现役中间件 | ≈1.6 G | 常驻 |
| VM | seata-server | ≈0.5 G | 只在验证/对照压测时 |
| 宿主 | IDEA + 服务（`-Xmx192m`） | ≈2.0 G | 常驻 |
| 宿主 | ES（`-Xms512m -Xmx512m`） | ≈1.2 G | 只在检索开发/压测时（实测宿主可用 4.3 G） |

**一次只起一个重组件**；起 ES 前关掉无用浏览器标签；实验结束停掉。

---

## 七、ES 侧实测结果（2026-10-11 21:0x，真跑出来的）

| 验证项 | 命令 / 方法 | 结果 |
|---|---|---|
| 版本对齐 | `GET /` | `llmall-es` / **7.17.10** / lucene **8.11.1**（与 Boot 管理的客户端同版本） |
| 堆被限制住 | `GET /_nodes/stats/jvm` | `heap_max = 512 MB`（不是默认的 1g/4g） |
| IK 装上且生效 | `POST /_analyze` | `ik_max_word` → 望/京/小/腰/烧烤店/烧烤/店；`ik_smart` → 望/京/小/腰/烧烤店 |
| 索引与 mapping | `PUT /shop_search` + `GET /_mapping` | `name/typeName/address` 挂 IK；`location` = `geo_point`；`hotScore` = double |
| 读模型灌数 | `EsBulkLoad.java`（JDBC → `_bulk`） | 30 条，`errors:false` |
| 关键词检索 | function_score 查询 | "美食" 命中 9 条（**类型词命中 → typeName 生效**）、"火锅" 命中 2 条（IK 分词命中） |
| 综合排序 | 同上 | 得分 31.5~33.4 有区分度：热度取对数后，评价数/评分能改变次序（Mamala 评价 9529 反超热度更高的海底捞） |
| geo 距离 | `_geo_distance` 排序 | 以杭州(30.30,120.14) 为中心：0.82 → 1.55 → 1.64 → 1.91 → 1.99 km 严格升序 |

### 7.1 实测踩到的三个坑（都已在脚本/文档里固化）

1. **堆不能用 `jvm.options` 改**：7.17 里 `-Xms/-Xmx` 是注释掉的（注释写着"heap size is
   automatically configured by Elasticsearch"），按行替换是**静默无效**的，会以默认堆起。
   正确做法：`config/jvm.options.d/heap.options` 放 `-Xms512m -Xmx512m`（官方推荐，后加载生效）。
2. **启动必须用 `ES_JAVA_HOME`**：本机 `JAVA_HOME=D:\develop\JDK`（Java 25），ES 批处理优先用它，
   而新 JDK 已移除 Security Manager → `Error: A command line option has attempted to allow or
   enable the Security Manager`，VM 直接起不来。指向自带 JDK（本包是 OpenJDK 20）即可。
   已生成 `D:\develop\elasticsearch-7.17.10\start-es.ps1` 固化这个环境变量。
3. **读模型的 x/y 是后加列**：30 行 `shop_search_doc` 是我加列之前由服务重建的，x/y 全为 NULL，
   灌进 ES 后 `location` 为空 → geo 查询 0 命中。已跨库回填（`llmall-shop.shop` → `llmall-search.shop_search_doc`，
   30/30 有了坐标）；正式做法是加列后调 `POST /search/view/rebuild`（服务会带上坐标）。

### 7.2 服务层验证（已完成，走的是完整 Java 链路）

用装好的 Maven（`D:\develop\apache-maven-3.9.6`，`JAVA_HOME=jdk11`）构建：
`mvn -s D:\Maven\settings.xml -pl llmall-search-service -am -DskipTests package` → **BUILD SUCCESS**（33s）。

| 验证项 | 命令 | 结果 |
|---|---|---|
| 服务按注解自动建索引 | 启动日志 + `GET /shop_search/_mapping` | `name`=text/ik_max_word、`location`=geo_point、`eventTimestamp`=date/epoch_millis |
| 空索引时的对账 | `GET /search/engine` | `esDocs:0, consistent:false` + 提示"调 POST /search/index/rebuild 重建" |
| Java 侧全量重建 | `POST /search/index/rebuild` | 写入 **30 条，336ms**；紧接着立刻查对账 → `consistent:true` |
| ES 检索 | `GET /search?keyword=美食` | 9 条，首条 Mamala（function_score 口径；MySQL 版按纯热度会先出海底捞） |
| 附近检索 | `GET /search/nearby?x=120.14&y=30.30` | 30 条，0.82/1.55/1.64 km 升序，`distance` 有值 |
| 回落可用性 | 修 bug 之前的那两次请求 | ES 报错时**没有 500**，自动回落 MySQL 并在日志打 warn（设计目标达成） |

### 7.3 实跑才发现、只看代码看不出来的四个坑（都已修）

| # | 现象 | 根因与修法 |
|---|---|---|
| 1 | `x_content_parse_exception: failed to parse field [must]`，嵌套 `number_format_exception: For input string: "2^1.0"` | `multiMatchQuery(kw, "name^3", ...)` 这种"字段带权重字符串"的写法被 HLRC 二次解析成 `2^1.0`。改用显式 API：`.field("name", 3f).field("typeName", 2f)...` |
| 2 | `Unable to convert value '2026-10-10T18:40:56' to java.time.LocalDateTime` | SDE 4.4 对 `LocalDateTime` 的处理：无 format 时写成 `yyyy-MM-dd`（**丢时分秒**），读回又格式不匹配。改用 epoch 毫秒 Long + `format = DateFormat.epoch_millis` |
| 3 | 重建 30 条后立刻查对账仍是 `esDocs:0` | ES 默认 1s 才 refresh。重建是低频运维动作，在 `reindexFromReadModel()` 末尾显式 `indexOps().refresh()` |
| 4 | `location` 全为空 → geo 查询 0 命中 | 读模型 x/y 是后加列，旧行没数据。跨库回填 + 正式路径（`POST /search/view/rebuild` 会带上坐标） |

> 另外两个环境级的坑（已在安装脚本里固化）：堆必须用 `config/jvm.options.d/*.options` 覆盖（`jvm.options` 里是注释掉的）；
> 启动必须设 `ES_JAVA_HOME` 指向自带 JDK 20（本机 `JAVA_HOME` 是 Java 25，ES 批处理优先用它 → Security Manager 报错起不来）。

### 7.4 还没做的（下一步）

- ~~**Seata 验证**：等服务端在 VM 上起来~~ → 已完成，见第 8 节。
- **对照压测**：Seata（`seata.enabled=false/true`）与检索（`llmall.search.engine=mysql/es`）各压一轮出数据 —— 开关都已就位，只差跑。

---

## 八、Seata 服务端部署与验证（2026-10-11 22:2x，全部实跑）

### 8.1 部署（我通过 SSH 直接做在你的 VM 上）

| 项 | 结果 |
|---|---|
| 镜像 | `seataio/seata-server:1.4.2`（272MB，走你配的 `docker.1ms.run` 镜像源） |
| 建表 | `seata` 库建好 `global_table` / `branch_table` / `lock_table`（脚本收进 `sql/merged/03-seata-server.sql`，因为**镜像里不含发行包的 script 目录**，实测 find 不到） |
| 配置 | `/root/seata/{registry.conf,file.conf}`；`config.type=file` + `store.mode=db`（日志有 `DruidDataSource inited` 证明 db 模式生效） |
| 网络与启动 | **`--network local-life`**（与 mysql/nacos/mq 同一网络）、发布 8091（TC）/7091（控制台）、`--memory 512m`、`--restart unless-stopped` |
| 容器互访 | 实测容器内 `getent hosts mysql` → 172.19.0.2、`getent hosts nacos` → 172.19.0.3：确实同网络、可按容器名互访 |
| 注册 IP 的正确解法 | 容器加 `--hostname ubuntu-tlias --add-host ubuntu-tlias:192.168.189.128`：容器内 `/etc/hosts` 里这条**排在 Docker 自动写的那条前面**，`getLocalHost()` 因此解析到局域网 IP，注册正确；容器仍留在 local-life 里（两全） |
| 注册 | Nacos `SEATA_GROUP/seata-server` → `192.168.189.128:8091 healthy=True`（实测；另有一条 172.19.0.5 的过期条目会自行过期） |

> 演进记录：最初为了绕开"注册成容器内网 IP"，先用了 `--network host`（能注册对，但容器不在共享网络里）；
> 后来发现根因是"Seata 按主机名取本机 IP + 容器内主机名指向容器 IP"，于是改成
> `local-life` + `--hostname/--add-host` 的组合 —— 既同网络，注册 IP 也正确。
> 换网络后**复验过一次完整下单**：订单 2109300022612725762、库存 9999→9998、
> TC 日志 `Begin → 2×Register branch → Committing ... successfully done`。

### 8.2 部署时踩的两个坑（都已解决并记录）

1. **注册成容器内网 IP `172.19.0.5` / docker0 网关 `172.19.0.1`**：Windows 上的服务根本连不到。
   根因链：① 该镜像的 ENTRYPOINT 是一串**写死的 java 命令**（不经过 shell），所以 `SEATA_IP`、`JAVA_OPTS`
   这类环境变量**根本传不进 JVM**（官方文档里说的 SEATA_IP 在这版镜像上不生效）；
   ② Seata 取本机 IP 会先按**主机名**解析，而 VM 的 `/etc/hosts` 是 `127.0.1.1 ubuntu-tlias`（回环，被过滤），
   于是退化成按网卡枚举，选中了 docker0。
   修法：把 `/etc/hosts` 里主机名指到 VM 的局域网 IP（`192.168.189.128 ubuntu-tlias`）→ 注册即正确。
   （已备份原文件；这是标准做法，对其它服务无影响。）
2. **HOCON 语法**：`registry.conf` / `file.conf` 的字段必须用换行或逗号分隔，写成 `{ a=1 b=2 }` 会解析失败
   （typesafe-config 报错，容器反复重启）。

> 另有一条**版本事实**与设计文档不符：SCA 2021.0.4.0 带的 Seata **客户端是 1.5.2**（日志：`client version:1.5.2, server version:1.4.2`），
> 不是文档里写的"1.4.2 对齐"。目前 1.5.2 客户端 + 1.4.2 服务端**实测可用**（注册与两阶段提交都正常）；
> 要彻底对齐应把服务端升到 1.5.2（注意 1.5 的配置迁移为 `application.yml`）。

### 8.3 验证证据

**注册（TC 日志）**：`TM register success (llmall-item-service / llmall-trade-service)`、
`RM register success (resourceIds=jdbc:mysql://...llmall-item / ...llmall-trade)`。

**提交路径**（成功下一单，商品 317578 × 1）：

```
Begin new global transaction applicationId: llmall-trade-service, transactionServiceGroup: llmall-tx-group,
                              transactionName: llmall-create-order, timeout:60000, xid=192.168.189.128:8091:2531855678756409345
Register branch successfully ... resourceId=jdbc:mysql://...llmall-item , lockKeys = item:317578;stock_flow:142
Register branch successfully ... resourceId=jdbc:mysql://...llmall-trade, lockKeys = order_detail:102;order:2109290165633540098
Committing global transaction is successfully done, xid = ...409345
```

业务效果：订单 81→**82**、库存流水 140→**141**、商品库存 10000→**9999**；
提交后 `llmall-trade.undo_log` / `llmall-item.undo_log` 与会话表都**已清理为 0 行**（AT 的正确行为）。

**回滚路径**（商品 2120808 库存 1 件，买 2 件）：

```
Begin new global transaction ... xid=...358
Rollback global transaction successfully, xid=...358
```

接口返回 `{"code":400,"msg":"库存不足，下单失败"}`，且业务零副作用（订单仍 82、库存流水仍 141、库存仍 1）。

> **未覆盖的一格**：本流程里"参与方分支已提交、随后失败由 undo_log 反向补偿"的场景没有天然注入点 ——
> 下单链路的分支顺序是 [trade 本地事务 → item 扣库存（分支提交）→ 券锁定（afterCommit，失败走显式补偿）]，
> 券核销只有在**并发抢同一张券**时才会失败。要补这一格有两条路：① 造券数据 + 并发双提交制造竞态；
> ② 加一个受 `llmall.maintenance.enabled` 保护的故障注入探针接口。**这一步没做，别在材料里写成"已验证补偿回滚"**。

---

## 九、2026-10-12 复查：7091 控制台的真相 + 时区统一（都已在 VM 实测）

### 9.1 7091 从来没通过，因为 **1.4.2 镜像里根本没有控制台**

| 证据（只读实测） | 结果 |
|---|---|
| `docker port seata-server` | `7091/tcp -> 0.0.0.0:7091` —— 端口**发布是好的** |
| 容器内 `netstat -lntp` | 只有 `:::8091`（PID 1 java）在监听，**7091 无人监听** → 宿主连它是"积极拒绝" |
| `/seata-server/libs`（159 个 jar）| **没有任何 console jar** |
| `/seata-server/classes/io/seata/server/` | 只有 `lock/env/event/metrics/session/transaction/logging/store/auth/storage/coordinator`，**无 console 包**；`find -iname "*Console*"` 为空 |
| 镜像自带的 `resources/registry.conf` | 连 console 配置段都没有（构建时间 2021-05-20） |
| 启动日志 | 只有 `Server started, listen port: 8091`，无控制台启动行 |

**结论**：Seata 的用户控制台是 **1.5.1** 才引入的，1.4.2 及更早没有这段代码。

- 本文第 3 节把"`http://192.168.189.128:7091` 可开"写成验收标准 —— **在 1.4.2 上永远无法通过**，当时没真验过；
- `docker/docker-compose-addon.yml` 原注释"控制台端口（1.4.2 起提供）" **已改**为说明 1.5.1+；
- 要用控制台就得**把服务端升到 1.5.x**（顺带修掉第 8.2 条记的"客户端 1.5.2 ↔ 服务端 1.4.2"错配，Seata 官方要求**服务端 ≥ 客户端**）。升 1.5.x 的代价：配置从 `registry.conf`/`file.conf` 迁到 `application.yml`，容器要重建，重建后需复验 Nacos 注册 + 一次真实下单。

### 9.2 时区统一到 Asia/Shanghai（东八区）

原本：VM 是 `Etc/UTC`，`nacos`/`mq`/`seata-server` 打 UTC 日志，`mysql` 因为建容器时带了 `TZ=Asia/Shanghai` 反而是 CST。

做了四件事（**只 restart，没有 recreate，数据零风险**；切换时无在途事务，`seata.global_table` 0 行）：

1. `timedatectl set-timezone Asia/Shanghai`（VM 系统）；
2. `nacos`：`/etc/localtime` 从 `Etc/UTC` 改指 `Asia/Shanghai`（它原本靠 `TZ` 环境变量已经是 CST）；
3. `mq`：rabbitmq 镜像**不带 tzdata**，把宿主 `/usr/share/zoneinfo/Asia/Shanghai` 拷进容器当 `/etc/localtime`；
4. `seata-server`：1.4.2 是 Alpine 镜像，**`jre/lib/zi` 时区库都不存在** —— 只挂 `/etc/localtime` 时 JVM 仍然打 UTC 日志；补上 `/usr/share/zoneinfo` 并把 `jre/lib/zi` 指向它之后，JVM 日志才变成 CST。

复验：VM 与四个容器 `date` 全为 CST；`nacos` 日志 `11:20:41`、`mq` 日志 `11:20:27`、`seata` 日志 `11:22:13`（此前都是 `03:xx`）；`mysql` `@@system_time_zone=CST`、`NOW()` = CST；Seata 仍以 `192.168.189.128:8091 healthy=true` 注册在 Nacos `SEATA_GROUP`。

> ⚠️ 手工 `docker cp`/`ln` 的改动只在容器可写层：`docker restart` 与 VM 重启都在，但**容器被 recreate 就丢**。
> 因此 `docker-compose-infra-lite.yml` 与 `docker-compose-addon.yml` 已补 `TZ` + `/etc/localtime`
> （Seata 另加 `/usr/share/zoneinfo`）挂载，重建即自带 CST。细节见 `llmall/deploy/部署说明.md` 第八节。

### 9.3 顺带查清"为什么老是要手动重启容器"

| 容器 | restart 策略 | 后果 |
|---|---|---|
| `mysql` | **`no`** | **VM 重启后不会自己回来** —— 必须手动 `docker start` |
| `mq` | **`no`** | 同上 |
| `nacos` | `always` | 能自愈 |
| `seata-server` | `unless-stopped` | 能自愈（本次 VM 开机后 02:55 自己起来了） |

治本（未执行，等确认）：`docker update --restart unless-stopped mysql mq` + 确认 `systemctl is-enabled docker` 为 `enabled`。

---

## 十、Seata 服务端升级到 **1.5.2**（2026-10-12，已实测完成）

### 10.1 为什么必须升（不是"可选优化"）

1. **官方要求服务端 ≥ 客户端**，而 SCA 2021.0.4.0 的 BOM 里客户端就是 **1.5.2**（`seata-all-1.5.2.jar`），1.4.2 服务端属反方向错配；
2. 7091 控制台**只有 1.5.1+ 才有**（第 9.1 节已用镜像内容证明 1.4.2 里没有 console 代码）。

### 10.2 升级过程中踩到、且"照文档做会失败"的三件事

| # | 坑 | 事实与修法 |
|---|---|---|
| 1 | **`--hostname/--add-host` 那套注册 IP 修法在 1.5.2 失效** | 1.5.2 换成了 `io.seata.server.env.ContainerHelper` 决定注册 IP：容器启动后仍把 **172.19.0.2（容器内网）** 注册进 Nacos。正解是环境变量 **`-e SEATA_IP=<宿主IP>`**（实测：不设 → 注册 172.19.0.2；设了 → 注册 192.168.189.128，healthy=true） |
| 2 | **1.5.2 的库表结构与 1.4.2 不同，直接换镜像会坏** | ① `lock_table` 新增 `status` 列 + `idx_status`/`idx_xid_and_branch_id` 两个索引；② 新增第 4 张表 **`distributed_lock`**，且**必须插 4 行种子**（AsyncCommitting / RetryCommitting / RetryRollbacking / TxTimeoutCheck，定时任务抢锁用）；③ `global_table` 索引改名；④ 字符集统一 utf8mb4。官方 DDL 已收进 `sql/merged/03-seata-server.sql`（全新安装）与 **`03b-seata-server-1.5.2-upgrade.sql`**（从 1.4.2 升级，幂等） |
| 3 | **配置格式换代** | 1.5.0 起服务端是 Spring Boot 应用，1.4.2 的 `registry.conf`/`file.conf` **在 1.5.2 镜像里已不存在**；配置改由 `docker/seata/application.yml`（registry=nacos、store=db、console 账号）承载。旧的两个 .conf 保留仅供 1.4.2 参考，compose 已不再挂载 |

> 另：VM **直连 registry-1.docker.io 被拒**（`dial tcp 122.248.226.57:443: connection refused`），`docker.1ms.run` 在下 blob 时 `connection reset by peer`，最终从 **`docker.m.daocloud.io`** 拉到并打回 `seataio/seata-server:1.5.2` 标签。换镜像源时记得这条。

### 10.3 验收证据（全部实跑）

| 验证项 | 证据 |
|---|---|
| 版本对齐 | llmall-item-service 日志：`register RM success. client version:1.5.2, **server version:1.5.2**`（此前是 server version:1.4.2） |
| 控制台可用 | `curl -o /dev/null -w %{http_code} http://127.0.0.1:7091/` → **200**；容器内同时监听 8091 与 7091；日志有 `seata-console` 的 Tomcat + Spring Security 启动链 |
| 注册正确 | Nacos `SEATA_GROUP/seata-server` → `instanceId=192.168.189.128#8091#default#SEATA_GROUP@@seata-server`，`healthy=true` |
| db 模式 | 启动日志 `DruidDataSource {dataSource-1} inited`；运行期会话写在 `seata.global_table/branch_table/lock_table` |
| **AT 提交路径** | 真实下单（POST /orders，商品 317578 × 1）：<br>`Begin new global transaction ... xid=...2727`（12:32:29）<br>`Register branch successfully ... resourceId=llmall-item, lockKeys=stock_flow:145;item:317578`<br>`Register branch successfully ... resourceId=llmall-trade, lockKeys=order:2109502444081766401`<br>`Committing global transaction is successfully done`（12:32:32） |
| 业务效果 | 订单 83 → **84**；商品库存 9998 → **9997**；库存流水 142 → **143**；提交后 `llmall-item.undo_log` / `llmall-trade.undo_log` 与 seata 三张会话表**全部归 0**（AT 的正确行为） |
| 回滚路径 | 同一环境下另一次失败下单（缺内部凭证导致 `user_id` 为空）走了 `Rollback global transaction successfully`，且随后被 `RetryRollbacking` 复核为已回滚 |

### 10.4 回滚方案（如需退回 1.4.2）

旧容器已 **停用并改名保留**为 `seata-server-1.4.2`（镜像 `seataio/seata-server:1.4.2` 也还在），
且新版 DDL 对 1.4.2 **向后兼容**（多出的 `status` 列有默认值、多出的 `distributed_lock` 表 1.4.2 不读），
所以回滚只需：`docker rm -f seata-server && docker rename seata-server-1.4.2 seata-server && docker start seata-server`。

### 10.5 本次同步修改的文件

| 文件 | 改动 |
|---|---|
| `docker/seata/application.yml` | **新增**：1.5.2 的完整配置（Nacos 注册 / file 配置源 / db store / console 账号） |
| `docker/docker-compose-addon.yml`、`docker/docker-compose-infra-lite.yml` | 镜像改 `1.5.2`；挂载改 `application.yml`；补 `SEATA_IP`；保留 TZ 与 zoneinfo 挂载 |
| `sql/merged/03-seata-server.sql` | 更新为官方 1.5.2 DDL（4 张表 + 种子，`INSERT IGNORE` 可重跑） |
| `sql/merged/03b-seata-server-1.5.2-upgrade.sql` | **新增**：1.4.2 → 1.5.2 的幂等升级脚本（判断后再 ALTER） |
