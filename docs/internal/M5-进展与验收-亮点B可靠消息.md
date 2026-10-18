# M5 进展与验收：亮点 B「跨域最终一致性」——可靠消息链路

> 本轮实现"本地消息表 + MQ 最终一致性 + 消费幂等 + 定时重试 + 对账补偿"，
> 覆盖的场景：**一次订单支付，要触发用户域加积分、门店域加销量，允许延迟但不能丢**。

```
支付成功回调
   │  llmall-trade-service（同一个本地事务）
   ├─ 1. 条件更新订单：status 1→2（幂等：重复回调不会把已取消订单改回已支付）
   └─ 2. 写入 message_log（待发送）  ←── 与订单更新同一事务，业务成功 ⇒ 消息一定存在
                │ 事务提交
                ▼
        dispatchAfterCommit()：立即投递 order.paid
                │ 投递失败？→ 定时任务按指数退避重试（5s→10s→20s…上限 10min）
                ▼
        MQ: llmall.trade.topic / order.paid（一条消息，两个消费者 = 扇出）
        ├── llmall-user-service：加积分 + 记积分流水（幂等）
        └── llmall-shop-service：累加门店销量 + 重算热度（幂等）
                ▼
   对账任务（每 5 分钟）：问下游"这笔订单你到底处理了没有" → 没处理就补偿重发
```

---

## 一、本轮交付

### llmall-trade-service（生产端）

| 文件 | 说明 |
|---|---|
| `domain/po/MessageLog.java` | 本地消息表实体（含状态机与退避字段） |
| `mapper/MessageLogMapper.java` | — |
| `service/IMessageLogService.java` + `impl/MessageLogServiceImpl.java` | `record`（同事务登记）/ `dispatchAfterCommit` / `dispatch` / `retryPending` / `compensate` |
| `mq/OrderEventFactory.java` | 统一构造 `OrderPaidEvent`（正常路径与补偿路径必须产出完全一致的消息） |
| `task/MessageRetryTask.java` | 定时重发（默认 15s 一轮） |
| `task/OrderReconcileTask.java` | 对账补偿（默认 5 分钟一轮，只对账最近 24h） |
| `OrderServiceImpl.java` | 支付成功埋点 + 4 处缺陷修复（见下） |
| `TradeApplication.java` | `@EnableScheduling` |

### 消费端

| 服务 | 文件 | 动作 |
|---|---|---|
| llmall-user-service | `listener/OrderPaidListener.java`、`service/impl/PointsServiceImpl.java`、`domain/po/PointsFlow.java`、`ConsumeRecord.java` | 加积分 + 记流水（`user_info.credits` 原子自增） |
| llmall-user-service | `controller/PointsController.java` | 对账接口 `GET /points/orders/{orderId}/applied` |
| llmall-shop-service | `listener/OrderPaidListener.java`、`ShopMapper#addSold`、`IShopService#applyOrderSold` | 累加门店销量 + 重算热度 |
| llmall-shop-service | `ShopController` | 对账接口 `GET /shops/orders/{orderId}/consumed` |

### 契约与 DDL

- `llmall-api`：`OrderPaidEvent`、`UserClient#isPointsApplied`、`ShopClient#isOrderConsumed`（含降级）
- `llmall-common`：`MqConstants` 增加交易域交换机/队列/业务类型；`points_flow` **从营销域移到用户域**（积分是用户资产，流水必须与资产同库同事务）

---

## 二、本轮修掉的 4 个真实缺陷（其中 1 个是资损级）

| # | 缺陷 | 危害 | 修复 |
|---|---|---|---|
| 1 | **`total += item.getPrice();` 漏乘购买数量** | **资损级**：买 3 件只按 1 件收钱 | `total += item.getPrice() * itemNumMap.get(item.getId())` |
| 2 | 恢复库存时把数量取负、复用**扣减**接口 | 销量被减成负数；且新扣减 SQL 的 `stock >= num` 语义被绕过 | 改用专用 `restoreStock`（正数 + 业务号幂等 + 销量 `GREATEST` 兜底） |
| 3 | `markOrderPaySuccess` 无条件 `updateById` | 支付回调重复投递时，会把**已取消**的订单改回已支付，并重复触发下游动作 | 条件更新 `WHERE status = 1`，返回 false 即视为已处理 |
| 4 | `cancelOrder` 幂等缺失 | 重复的延迟消息会重复回滚库存（库存凭空变多） | 条件更新 `WHERE status = 1` 才关单并回滚 |

---

## 三、三层幂等，各管一件事（核心要点）

| 层 | 载体 | 作用 |
|---|---|---|
| **生产幂等** | `message_log(biz_type, biz_id)` 唯一键 | 同一笔业务只登记一条消息，重复触发不会重复发 |
| **消息级消费幂等** | `consume_record(biz_type, biz_id, consumer)` 唯一键 | 挡住 MQ 的重复投递（at-least-once 是必然，不是意外） |
| **业务级幂等** | `points_flow / stock_flow` 的 `(biz_type, biz_id, type/item_id)` 唯一键 | 兜住"消息没重复但业务被调了两次"的情况，同时**作为对账判据** |

> 为什么三层都要：MQ 只保证"至少一次"，任何"靠 MQ 不重复"的设计都是错的；
> 而只有业务表上的唯一键，才能回答"这笔业务到底做没做"这个对账必须回答的问题。

---

## 四、两种消费端写法（都对，但要知道差别）

| 写法 | 代表 | 说明 |
|---|---|---|
| **占位与业务同一事务** | `PointsServiceImpl.applyOrderPoints` | 全部在一个本地事务里，失败整体回滚，MQ 重投即可重试，代码最简单 |
| **占位 + 失败撤销占位** | `ReviewCreatedListener` / shop 的 `OrderPaidListener` | 业务方法各自带事务、不在同一事务时使用；**失败必须删除占位再抛异常**，否则消息永久被当成"已处理" |

关键判据只有一句：**要么占位与业务在同一事务，要么失败能撤销占位**。两者都不满足 = 静默丢数据。

---

## 五、验收与故障演练（这是对外材料里那句"零丢单"的证据来源）

前置服务：`gateway / llmall-user-service(8084) / llmall-shop-service(8087) / llmall-trade-service(8085) / llmall-pay-service(8086)` + MySQL / Redis / **RabbitMQ** / Nacos。

### 正常链路
```powershell
# 1) 确认 RabbitMQ 里出现两个队列（自动声明）
#    user.points.order.paid.queue / shop.sold.order.paid.queue
# 2) 登录 → 下单 → 支付 → 观察日志顺序：
#    trade:  订单支付成功已受理 → 消息已登记并将在提交后投递 → 消息投递成功
#    user:   积分发放成功: orderId=..., points=...
#    shop:   门店销量已累加: shopId=..., num=...
# 3) 校验数据
#    SELECT * FROM `llmall-order`.message_log WHERE biz_type='ORDER_PAID';
#    SELECT * FROM `llmall-user`.points_flow WHERE biz_id='<orderId>';
#    SELECT sold FROM `llmall-shop`.shop WHERE id=<shopId>;
```

### 演练 1：消费者停机 30 秒（验证不丢消息）
1. 停掉 llmall-user-service；
2. 对一笔新订单完成支付（消息投递成功，但没有消费者）；
3. 观察 `message_log.status = 1（已投递）`、`consume_record` 无记录；
4. 重新启动 llmall-user-service → 消息被消费 → `points_flow` 出现记录、`credits` 增加。
> 结论：**消息不因消费者宕机而丢失**（MQ 持久化 + 手动/自动 ack 语义）。

### 演练 2：重复投递 1000 次（验证幂等）
在 RabbitMQ 控制台对 `user.points.order.paid.queue` 重复 Publish 同一条消息，
观察日志出现"该订单消息已消费过，幂等跳过"，且 `credits` **只增加一次**、`points_flow` 只有一条。

### 演练 3：对账补偿（验证最后一道防线）
1. 手工删除 `consume_record` 与 `points_flow` 中该订单的记录（模拟"下游处理后数据被误删/未处理"）；
2. 等对账任务触发（默认 5 分钟），观察 llmall-trade-service 日志：
   `对账发现下游未处理: orderId=..., pointsApplied=false` → `触发对账补偿重发`；
3. 随后 llmall-user-service 重新处理并补齐积分。
> 结论：即使消息彻底丢失，**对账也能把数据找回来**。

---

## 六、常见追问预判

| 追问 | 答题要点 |
|---|---|
| 为什么不用 Seata 一步到位？ | 下单扣库存这类"用户等结果"的链路适合强一致（Seata AT）；加积分/加销量/发通知这类"允许延迟"的动作放到强一致里只会拉长事务、锁表时间变长 → **按一致性要求分层**，并且**双方案都实现并压测对比**（M6 补） |
| 消息丢了怎么办？ | 三道防线：本地消息表（同事务不丢）→ 定时重试（投不出去时）→ 对账补偿（投出去了但下游没处理时） |
| 消息重复怎么办？ | 三层幂等（见第三节），绝不假设 MQ 不重复 |
| 为什么消息要"提交后"再发？ | 避免消费者先于提交读到数据（时序问题）；但也要说明 afterCommit 不等于可靠投递 |
| 重试会不会把下游打死？ | 指数退避 + 上限 10 分钟 + 批量上限 50 条/轮；对账在下游不可用时保守跳过（返回 true），避免补偿风暴 |
| 对账为什么是"问下游"而不是"看自己发成功没"？ | 发送成功 ≠ 消费成功。只有下游能回答"我处理了没有"，所以需要一个**可查询的判据**（积分流水唯一键 / 消费记录） |
| 长期堆积怎么办？ | message_log 设 status 生命周期，已投递的记录定期归档；`idx_status_retry(status, next_retry_time)` 支撑扫描 |

---

## 七、剩余工作（下一轮）

**亮点 B 的第二条腿**：引入 **Seata AT** 实现"下单扣库存 + 券核销"的强一致分支，
并做**同场景双方案对比压测**（TP99 / QPS / 锁等待 / undo_log 增长），产出对比数据 —— 这是对外材料里最有分量的一条。
