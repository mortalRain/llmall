# M4 进展与验收：亮点 C「评价 → 门店热度 → 榜单排序」闭环打通

> 本轮把亮点 C 的**跨服务链路**接起来了：内容域发布评价 → MQ → 门店域更新评分并重算热度 → 榜单 ZSet 与列表排序随之变化。
> 三个域之间**没有任何直接调用**，只通过事件耦合 —— 这正是"跨域最终一致性"在业务上的第一次落地。

```
用户发布评价
   │  llmall-content-service
   ├─ 1. 跨服务校验门店存在（Feign 调 llmall-shop-service）
   ├─ 2. 评价落库（llmall-content.blog）
   ├─ 3. 写扩散推送到粉丝 Feed 收件箱（超阈值则跳过）
   └─ 4. 事务提交后投递 review.created 事件（content.topic）
                     │
                     ▼  MQ
              llmall-shop-service（shop.hot.review.queue）
   ├─ 5. 幂等占位（consume_record 唯一索引）
   ├─ 6. 原子更新门店：评价数 +1、平均分重算、刷新最近评价时间
   ├─ 7. 重算热度分（base × 时间衰减）→ 写 DB + 榜单 ZSet + 失效详情缓存
   └─ 8. 投递 shop.hot.changed 事件 → llmall-search-service 同步 ES 索引（M6 接入）
```

---

## 一、本轮交付

### llmall-content-service（内容域，端口 8089，库 llmall-content）

| 文件 | 说明 |
|---|---|
| `domain/po/Blog.java` | 探店笔记/评价（点评 tb_blog 改造）：**新增 score（1~5）**，这是热度公式的输入 |
| `domain/po/BlogComments.java`、`BlogLike.java`、`Follow.java`、`FeedInbox.java` | 评论、点赞、关注、Feed 收件箱 |
| `mapper/*`（5 个） | 对应 Mapper |
| `domain/dto/BlogFormDTO.java` | 发布表单（校验评分 1~5，防止脏数据带偏热度） |
| `service/impl/BlogServiceImpl.java` | 发布/详情/点赞/取消/评论 + **提交后发事件** |
| `service/impl/FollowServiceImpl.java` | 关注/取关/共同关注/粉丝数 + **Feed 写扩散（纯推）** |
| `mq/ContentEventPublisher.java` | 事件投递 |
| `controller/BlogController.java`、`FollowController.java` | 对外接口 |
| `constant/ContentRedisConstants.java` | 点赞 ZSet、关注集合、写扩散阈值 |

**接口清单**
```
POST   /blogs                        发布评价（登录）
GET    /blogs/{id}                   笔记详情（公开）
GET    /blogs/shop/{shopId}          门店下笔记（公开）
POST   /blogs/{id}/like              点赞（幂等）
DELETE /blogs/{id}/like              取消点赞
GET    /blogs/{id}/liked              我是否点过赞
GET    /blogs/{id}/liked-users       点赞排行（ZSet）
POST   /blogs/{id}/comments          评论
GET    /blogs/{id}/comments          评论分页
POST   /follows/{userId}             关注 / DELETE 取关
GET    /follows/common/{userId}      共同关注（集合求交集）
GET    /follows/feed?lastId=         我的 Feed（滚动分页）
```

### llmall-shop-service（消费端）

| 文件 | 说明 |
|---|---|
| `listener/ReviewCreatedListener.java` | 消费 review.created：幂等 + 更新门店 + 重算热度 + 转发事件 |
| `mapper/ShopMapper.java` | 新增 `applyReview`：**一条 SQL 原子更新平均分** |
| `domain/po/ConsumeRecord.java` + Mapper | 消费幂等表 |
| `mq/ShopEventPublisher.java` | 投递 shop.hot.changed（供搜索域同步索引） |

### 共享层

- `llmall-common/constant/MqConstants.java`：交换机/路由键/队列名**契约统一收口**
- `llmall-common/config/MqConfig.java`：**修复**消息转换器（见下）
- `llmall-api/event/ReviewCreatedEvent.java`、`ShopHotChangedEvent.java`：事件契约

---

## 二、本轮修掉/规避的 4 个真坑（可直接展开讲）

### 1. MQ 消息转换器不认 Java 时间类型
原实现 `new Jackson2JsonMessageConverter()` 会**自建**一个干净的 ObjectMapper，因此：
- 不认 `LocalDateTime`（报 `Java 8 date/time type not supported by default`）；
- 不带项目 `JsonConfig` 里配置的 `Long → String` 序列化规则。
**修复**：注入 Spring 容器里的 ObjectMapper，让 MQ 序列化规则与 HTTP 接口一致。
（症状很隐蔽：接口正常、MQ 消费端报解析错。）

### 2. 先发消息 vs 先提交事务（时序问题）
如果在事务未提交时就发 MQ，消费者可能**先于提交**去读数据，读到旧值或读不到。
**做法**：用 `TransactionSynchronizationManager.registerSynchronization(...afterCommit)`，**提交成功后才投递**。
> 顺带说明：afterCommit 只解决"时序正确"，**不解决"可靠投递"** —— 提交后进程崩溃仍会丢消息，那需要本地消息表 + 重试 + 对账（M5 亮点 B）。

### 3. 消费幂等：先占位、失败必须撤销占位（最容易踩的坑）
- 先插入 `consume_record`（唯一索引挡住并发与重复投递），再处理业务；
- **处理失败时要删除该占位记录再抛出**，让 MQ 能重试。
- 如果失败却不撤销占位，这条消息会永远被当成"已处理" → **数据静默丢失**，而且没有任何报错。

### 4. MySQL 单表 UPDATE 的赋值顺序
更新门店平均分用一条 SQL 完成"读-算-写"，但**赋值顺序有讲究**：
```sql
UPDATE shop SET
  score = ROUND((score * comments + #{score} * 10) / (comments + 1)),  -- ← 必须在前，此时 comments 还是旧值
  comments = comments + 1                                              -- ← 必须在后
WHERE id = #{shopId}
```
MySQL 后面的赋值会看到前面刚赋的新值，若顺序写反，分母会多 1，**平均分被系统性算低**。
用一条 SQL 而不是"查出来→算→写回"，是为了避免并发评价下的丢更新。

---

## 三、亮点 C 的技术要点汇总

| 环节 | 做法 | 为什么这么做 |
|---|---|---|
| 热度公式 | `base = 评分×4 + log10(评论+1)×3 + log10(销量+1)×3`，再乘 `1/(1+Δ天/7)` | 评分权重最高；log 抑制刷量与爆款通吃；时间衰减给新店冷启动机会 |
| 衰减锚点 | 取 `last_review_time`（最近评价时间） | 若用"重算时间"，每次重算都把衰减重置成 1，衰减形同虚设 |
| 榜单读 | Redis ZSet `ZREVRANGE` | O(log N + M)，毫秒级；比 `ORDER BY` 全表排序便宜得多 |
| 榜单还原 | **按 ZSet 的 id 顺序还原**列表 | `WHERE id IN (...)` 返回顺序不保证与榜单一致，否则榜单乱序 |
| Feed | **纯推**：普通作者写扩散，粉丝 > 1000 **直接跳过**（该笔记对这批粉丝不可见） | 写扩散成本与粉丝数成正比；原设想的读时拉取未实现，见台账 C-01 |
| Feed 分页 | 游标分页 `id < lastId` | 避免 `OFFSET` 深分页性能塌陷与翻页重复/漏数据 |
| 点赞 | DB 唯一索引 + `liked = liked + 1` 原子自增 + Redis ZSet 排行 | 唯一索引即幂等；SQL 自增避免并发丢更新 |
| 关注 | DB 权威 + Redis 集合（求交集 O(min(n,m))） | 共同关注用 SQL 需要两次 JOIN，数据量大时明显更慢 |

---

## 四、验收步骤（环境就绪后，可直接复制执行）

前置：启动 `gateway(8080)`、`llmall-user-service(8084)`、`llmall-shop-service(8087)`、`llmall-content-service(8089)`，
以及 MySQL / Redis / **RabbitMQ** / Nacos（按 `资源受限运行方案.md` 的最小启动集）。

```powershell
$gw = "http://localhost:8080"

# 1) 登录拿 token（账号按你库里的实际数据调整）
$login = Invoke-RestMethod -Method Post -Uri "$gw/users/login" -ContentType "application/json" `
         -Body '{"username":"Jack","password":"123456"}'
$h = @{ authorization = $login.accessToken }
$uid = $login.userId

# 2) 造一条门店数据（首次需要一个门店；也可直接 INSERT 到 llmall-shop.shop）
#    INSERT INTO shop(name,type_id,images,address,x,y,avg_price,sold,comments,score,status)
#    VALUES('望京小腰烧烤',1,'a.jpg','望京SOHO',116.48,39.99,8000,0,0,0,1);

# 3) 记录发布前的热度（门店详情走缓存）
Invoke-RestMethod -Uri "$gw/shops/1" | ConvertTo-Json -Depth 3

# 4) 发布一条 5 分评价
$body = '{"shopId":1,"title":"好吃到爆","content":"烤串很香，服务也好","score":5}'
$blogId = Invoke-RestMethod -Method Post -Uri "$gw/blogs" -Headers $h -ContentType "application/json" -Body $body
Write-Host "blogId=$blogId"

# 5) 观察 llmall-shop-service 日志：应依次出现
#    评价发布成功 → review.created 已投递 → 门店热度已更新 → shop.hot.changed 已投递
#    查看消费者日志：该评价已处理/门店热度已更新（含 base、decay、hotScore 三个值）

# 6) 再次查看门店详情与榜单：comments/score/hotScore 应已变化，榜单里出现该门店
Invoke-RestMethod -Uri "$gw/shops/1" | ConvertTo-Json -Depth 3
Invoke-RestMethod -Uri "$gw/shops/hot?limit=5" | ConvertTo-Json -Depth 3

# 7) 幂等验证：手工重发同一条消息（RabbitMQ 控制台 → queue shop.hot.review.queue → Publish message，
#    或用重复投递脚本），日志应出现"该评价已处理过，幂等跳过"，且 comments 不再增加。
```

**期望结果**：`comments +1`、`score` 变为该评价的分数、`hotScore > 0`，且该门店出现在热度榜中；
重复投递不产生第二次计数。

---

## 五、常见追问预判

| 追问 | 答题要点 |
|---|---|
| 为什么评价和门店要拆两个服务？ | 内容与门店是两套生命周期与访问特征（内容写多读少、门店读极多），拆开可独立扩容；通过事件解耦，门店服务不需要知道评价的存在 |
| 消息丢了怎么办？ | 目前是"提交后投递"，仍可能丢；M5 用**本地消息表 + 重试 + 对账补偿**兜底（并说明这是刻意分阶段实现） |
| 消息重复怎么办？ | `consume_record` 唯一索引占位，失败撤销占位；不依赖"MQ 不重复"这种不成立的假设 |
| 热度为什么不用定时任务批量算？ | 批量算延迟高（T+1），且全量重算成本随门店数线性增长；这里只算"受影响的那一家"，O(1) |
| 榜单为什么用 ZSet 不用 ES？ | 榜单只需"按分数排序取 Top N"，ZSet 毫秒级且极省资源；ES 更适合多条件检索与分词（分工见设计文档） |
| 写扩散会不会被打爆？ | 已设阈值 1000，超阈值**直接跳过推送**（读时拉取未实现，这批粉丝看不到） |
| 平均分并发怎么保证不丢更新？ | 一条 SQL 原子完成，并说明 MySQL 赋值顺序的坑 |

---

## 六、下一步

**M5（亮点 B · 跨域最终一致性）**：下单链路（库存扣减 + 券核销 + 积分发放）落**本地消息表 + MQ 最终一致性 + 消费幂等 + 定时对账补偿**；
同时引入 Seata AT 做对照实现，产出**同场景双方案对比压测数据**。
