# llmall Redis 高可用（1 主 + 1 从 + 3 哨兵）运行手册

> **状态：已完成并端到端验证。**
> 自动故障转移实测 3~5 秒完成；应用改为哨兵模式后，主库宕机时**无需重启**即可自动跟到新主库。

---

## 0. 根因结论（最重要的一节，请先读）

**`rename-command CONFIG` 会让哨兵自动故障转移静默失效。**

现役从库配置里原来有这一条「安全加固」：

```
rename-command CONFIG "CONFIG_9f3a1c"      # ← 元凶，已删除
```

**机制**：哨兵对数据节点执行 `CONFIG` 命令来完成故障转移的「提升」阶段。`CONFIG` 被改名后，
这一步在哨兵侧**静默失败**（只在日志留一行，不进错误通道），流程卡在
`+failover-state-wait-promotion`，直到 `failover-timeout`（默认 180 秒）超时才放弃。
外部表现是「主库挂了但永远不会自动切换」，而且**完全不像是配置问题**。

**证据链**（用最小复现集群 7001/7002 + 27001-27003 逐项二分，每项单独跑一遍故障转移）：

| 变量组 | 自动故障转移结果 |
|---|---|
| 最简（无 rename、无 announce） | ✅ t+4s 成功 |
| 只加 `rename-command CONFIG` | ❌ **240s 未切换（失败）** |
| 只加 `rename-command SHUTDOWN` | ✅ t+4s 成功 |
| 只加 `rename-command FLUSHALL` + `FLUSHDB` | ✅ t+4s 成功 |
| 只加 `replica-announce-ip/port` | ✅ t+5s 成功 |
| 全加（四条 rename + announce + 180s 超时） | ❌ 失败 |
| 全加但**去掉 CONFIG 这一条** | ✅ t+4s 成功 |

**修复**：删除 `rename-command CONFIG`。其余三条 rename（`FLUSHALL`/`FLUSHDB`/`SHUTDOWN`）
经实测**不影响**故障转移，可以保留。安装脚本已加自检，一旦发现配置里有这条就直接报错拒绝安装。

**安全上不亏**：数据节点已有 `requirepass`，且只监听内网。用 `requirepass` + 网络隔离防护，
比「把 CONFIG 命令名藏起来」更可靠 —— 后者换来的是一套不可用的高可用。

---

## 1. 当前状态（2026-10-13 实测）

### 已验证可用 ✅

| 能力 | 证据 |
|---|---|
| **自动故障转移** | `failover-drill.sh` 全绿：`t+3s` 完成 `+switch-master`，9 项校验全部 PASS |
| **应用扛过切换** | 应用连上后停主库：哨兵 `t+5s` 切换，应用仅 1 次 2 秒超时后**自动恢复读写**（不重启） |
| 1 主 1 从复制 | 6379 `role:master` / 6380 `role:slave`，`master_link_status:up`，`connected_slaves:1`，数据量一致 |
| 3 个哨兵运行 | 26379/26380/26381 均 active，`num-other-sentinels=2`（互相发现正常），`flags=master` |
| 哨兵对外地址正确 | 下发 `<vm-ip>:6379`（**不是** 127.0.0.1，应用在 Windows 宿主上能连） |
| 应用配置已切哨兵 | Nacos `shared-redis.yaml` + 网关 `application.yaml` 均已改为 sentinel 模式（见第 5 节） |
| 切换后可写 / 不丢数据 | 新主库只读被正确解除；切换后数据量与切换前一致 |
| 主库数据未受影响 | 全程 6379 承载现役数据，应用侧 `PING → PONG` |

### 切换耗时

`down-after-milliseconds=3000` + 选举 + 提升，**实测 3~5 秒**。远快于默认
`failover-timeout=180s`（那只是放弃阈值，不是切换耗时）。

---

## 2. 排查过程中修掉的全部问题（含踩坑记录）

这些都是实测踩出来的，**不要再回退**：

| # | 问题 | 现象 | 处置 |
|---|---|---|---|
| 1 | **`rename-command CONFIG`**（根因） | 故障转移卡 `wait-promotion` 直到超时 | 删除该条 rename；安装脚本加自检 |
| 2 | Redis 没有 `timezone` 配置指令 | 服务拒绝启动：`Bad directive or wrong number of arguments` | 删掉该指令，改用 systemd `Environment="TZ=Asia/Shanghai"` |
| 3 | `redis-server --check-config` 参数不被接受 | 放配置文件前/后都报 `Bad directive` | 放弃预检，改用启动结果 + journalctl 校验 |
| 4 | Ubuntu 24.04 的 redis-server 包**没有** `redis-sentinel` | systemd `status=203/EXEC` | 改用 `redis-server <conf> --sentinel` |
| 5 | 哨兵 `protected-mode yes` + 无密码 → 只接受回环 | 哨兵之间互相标记 `s_down,disconnected`，凑不齐多数派 → `-failover-abort-not-elected` | 哨兵配置 `protected-mode no`（数据节点仍保留 `requirepass`） |
| 6 | 从库 `replicaof` 写 `127.0.0.1` 与哨兵 monitor 的 VM IP 不一致 | 哨兵每 10 秒刷 `+fix-slave-config` | 从库 `replicaof` 改为 VM IP；实测刷屏立刻停止 |
| 7 | `failover-timeout` / `parallel-syncs` 会从哨兵配置文件消失 | 静默回落默认 180000 | 模板保留这两行；核对要用**运行时口径**：`SENTINEL master llmall-master \| grep -A1 failover-timeout` |
| 8 | 哨兵失败后进入 `failover-timeout × 2` 冷却 | 日志 `Next failover delay: ...`；冷却期内主库再挂**完全没反应** | 连续演练前重启哨兵清冷却；分析日志先看有没有这条 |
| 9 | 演练脚本等待窗口过短（40s） | 实测切换可能 47s+，误判成"没切换"，据此反复去修正常集群 | 等待窗口放宽到 200s |
| 10 | 复位拓扑后哨兵仍缓存旧主库 | 复位明明成功，`get-master-addr-by-name` 仍返回旧值 | 复位后**重启哨兵**，让它按 monitor 行重新认识拓扑 |
| 11 | bash `local a="$1" b="$1$x"` 陷阱 | `set -u` 下报 `x: unbound variable`（多赋值先展开右侧） | 拆成多条 `local` |
| 12 | **故障转移会改写哨兵的 `monitor` 行** | 切换后 `sentinel-*.conf` 里的 `sentinel monitor llmall-master <IP> 6380` 变成新主库；此时若手工把拓扑改回 6379 主，哨兵仍监控 6380（而 6380 已是 slave）→ 三个哨兵全部 `flags=s_down,o_down,master`，且其中一个卡在 `failover_in_progress`，怎么等都不收敛 | 用 `install-redis-ha.sh --repair` 一键修复（改回 monitor 行 + 清零纪元 + 清 known-replica + 重启） |
| 13 | **cart-service 的 `bootstrap.yaml` 漏了两份共享配置** | `shared-configs` 里只有 `shared-jdbc`，没有 `shared-mybatis` 与 `shared-redis`。后果：Redis 退化成默认 `localhost:6379`（启动不报错、连接懒加载，直到第一次读写才 `Connection refused`），MyBatis 全局口径（`update-strategy: not_null` 等）也没生效 | 补齐两份。已在 `bootstrap.yaml` 里写清"漏了会怎样"；这是"配置没加载但功能看起来正常"的典型漂移 |
| 14 | **Nacos 配置的字符编码依赖 JVM 默认值** | Windows 上 `java -jar` 的 `file.encoding` 是 **GBK**，Nacos 客户端按平台编码解码 UTF-8 配置内容会抛 `MalformedInputException: Input length = 1`，**bootstrap 阶段直接起不来**。配置本身是合法 UTF-8（已用字节校验）。IDEA 里通常已设 UTF-8，所以只在裸跑 `java -jar` 时暴露 | 启动加 `-Dfile.encoding=UTF-8`（见下方"启动方式"一节） |
| 15 | **空集合没法用"key 不存在"表达**（本次新写的购物车缓存的坑） | 购物车空车时如果"字段全删"→ key 消失 → 下次读又未命中 → **空车用户每次刷新都打 DB**（缓存穿透），而空车用户（新用户 + 下单后清空）恰恰量最大。实测：删完条目后 key 直接没了，连续两次查询都打到 DB | 写一个占位字段 `__empty__` 把 key 撑住并设 TTL；读侧识别它并跳过（否则会多出一条 itemId 为 null 的假条目） |

---

## 附：启动方式（含编码要求）

Windows 上裸跑 `java -jar` 必须显式指定 UTF-8，否则 bootstrap 阶段读 Nacos 配置会抛
`MalformedInputException`（原因见上表第 14 条）：

```bash
java -Dfile.encoding=UTF-8 -jar llmall-shop-service.jar
```

IDEA 里跑通常已经设了（`Run/Debug Configurations` → VM options 加 `-Dfile.encoding=UTF-8`，
或 `Settings → File Encodings` 全部设 UTF-8），所以这个问题只在命令行裸跑时出现。

**根治办法**（换机器/换人之后不会再踩）：把 `-Dfile.encoding=UTF-8` 写进 Dockerfile 的
`JAVA_OPTS`、或 `deploy/docker-compose-apps.yml` 的环境变量、或统一的启动脚本里。
本项目还没有统一启动脚本，属于待补项。

> **最大的方法论教训**：一开始就该做最小复现。我在"生产形状"的集群上反复试错了十轮，
> 而最小复现集群 + 逐项二分只用了几轮就把根因锁定到**单条配置**。

---

## 3. 部署资产

| 文件 | 作用 |
|---|---|
| `install-redis-ha.sh` | 安装/卸载/查状态（幂等、支持续装、`--debug` 落盘、**自检拒绝 `rename-command CONFIG`**） |
| `redis-replica.conf.template` | 从库配置模板（`__VM_IP__` 由安装脚本替换） |
| `sentinel.conf.template` | 哨兵配置模板（三个哨兵共用，仅端口/路径不同） |
| `failover-drill.sh` | **故障转移演练 + 自动复位**（前置就绪检查、200s 等待窗口、复位后重启哨兵校验） |
| `lab-cluster.sh` | 最小复现实验集群（7001/7002 + 27001-27003，与现役完全隔离，支持 `up-ren-config` 等二分变量组） |

```bash
# 在 VM 内以 root 执行
bash install-redis-ha.sh --vm-ip <vm-ip> --password 123456
bash install-redis-ha.sh --status
bash failover-drill.sh                 # 演练（会短暂停主库，自带复位）
bash install-redis-ha.sh --uninstall   # 只删从库与哨兵，主库 6379 不动
```

部署位置：

```
/etc/redis/redis.conf              主库（原样保留）
/etc/redis/redis-replica.conf      从库      0.0.0.0:6380
/etc/redis/sentinel-26379.conf     哨兵 x3   0.0.0.0:26379/26380/26381
/var/lib/redis-replica             从库数据（maxmemory 96mb）
systemd: redis-replica.service、redis-sentinel@.service
```

---

## 4. 应急手工切换（自动切换失效时的兜底）

```bash
# 1. 确认 6379 确实不可用
redis-cli -p 6379 -a 123456 --no-auth-warning PING

# 2. 停哨兵，避免人工操作期间被插一脚
for p in 26379 26380 26381; do systemctl stop redis-sentinel@$p; done

# 3. 提升从库（实测 2 秒生效）
redis-cli -p 6380 -a 123456 --no-auth-warning REPLICAOF NO ONE
redis-cli -p 6380 -a 123456 --no-auth-warning INFO replication | grep ^role:   # 应为 master

# 4. 原主库修好后让它以从库身份回归（**必须做，否则形成双主**）
systemctl start redis-server
redis-cli -p 6379 -a 123456 --no-auth-warning REPLICAOF <vm-ip> 6380

# 5. 恢复哨兵
for p in 26379 26380 26381; do systemctl start redis-sentinel@$p; done
```

**注意**：改动拓扑后一定要**重启哨兵**，否则它仍按自己缓存的旧主库判断。

---

## 5. 应用侧接入（**已完成**）

`nacos-config/shared-redis.yaml` 已改为哨兵模式并**发布到 Nacos**；
`llmall-gateway/application.yaml` 那份独立配置也同步改了。完整内容见这两个文件，要点：

```yaml
spring:
  redis:
    password: ${llmall.redis.pw}
    connect-timeout: 2s
    timeout: 2s
    sentinel:
      master: llmall-master
      nodes: ${llmall.redis.host}:26379,${llmall.redis.host}:26380,${llmall.redis.host}:26381
```

### 接入前做过的验证（不是直接改上去的）

1. **宿主机连通性**：三个哨兵端口 `26379/26380/26381` 从 Windows 宿主可达，
   且 `SENTINEL get-master-addr-by-name llmall-master` 返回 `<vm-ip>:6379`。
2. **YAML 绑定**：用 Spring Boot 自己的 `Binder` 把这份配置绑成 `RedisProperties`，
   确认 `sentinel.master=llmall-master`、`sentinel.nodes` 解析出 **3 个**节点、
   口令/超时/池参数（含 `max-wait`）全部绑定成功。
3. **真实连接**：用 user-service 的 classpath 跑探针，经哨兵连到主库并读写成功，
   `角色 = master`；连接工厂为 `LettuceConnectionFactory`。
4. **扛过切换（最关键）**：应用连上后从外部停掉主库 ——
   ```
   [SURVIVE] t+28135ms 首次失败: RedisCommandTimeoutException（2 秒超时，正是配的 timeout）
   [SURVIVE] t+30164ms 恢复写入！期间失败 1 次，当前角色=master
   [SURVIVE] 汇总：写入成功 14 次，失败 1 次
   [SURVIVE] 结论：应用在故障转移后自动恢复（无需重启）
   ```
   哨兵侧同一时刻 `t+5s` 完成切换。**应用只经历一次 2 秒超时** ——
   这就是配 `timeout: 2s` 的价值：失败得干脆，而不是卡默认的 60 秒。

### 生效方式

换 Redis 连接配置需要**重启服务**（配置中心下发不会重建已有的 Redis 连接池）。
实测当时没有业务服务在运行，所以下次启动即生效。

### 注意事项

- `sentinel` 模式下 `host`/`port` 不再决定连接目标，保留只是为了不让各服务
  `application-local.yaml` 里的 `llmall.redis.host` 占位符悬空。
- Spring Boot 2.7 只有一个 `spring.redis.sentinel.password`，**无法**同时表达"哨兵口令"与
  "数据节点口令"，所以本方案刻意让哨兵不设密码（只监听内网，数据节点仍有 `requirepass`）。
- 网关那份配置不走 Nacos，改共享配置时**必须同步改它**，否则会出现
  "业务服务都切到新主库了、网关还连着死掉的主库"。

---

## 6. 已知局限（别当成完整高可用）

1. **三个哨兵与主从都在同一台 VM 上**。能防"Redis 进程崩溃/被 OOM 杀掉"，
   **防不了 VM 宕机/网络中断** —— 那种情况下三个哨兵一起死。真正的高可用需要 3 台独立机器。
2. **异步复制会丢写**。主库确认写入后、数据还没传到从库时主库挂掉，切换后这部分写入会丢。
   本项目里表现为：刚被轮换掉的 refresh token 可能"复活"、刚写入的黑名单条目可能丢失、
   秒杀预扣的库存/已发放计数回退。所以 `AuthGlobalFilter` 的降级策略（Redis 不可用时放行 +
   打 `【鉴权降级】` ERROR 告警）仍然需要保留。
3. **故障转移后原主库不会自动回归**为从库（它的配置里没有 `replicaof`）。
   人工/脚本必须补 `REPLICAOF`，否则形成双主。`failover-drill.sh` 的第 4、5 步就是在做这件事。
4. **故障转移会把新主库地址写进哨兵的 `monitor` 行**。所以"演练后复位"必须连带改回
   monitor 行并清纪元，否则哨兵会去监控一个已经变成 slave 的节点，表现为全部 `s_down`、
   其中一个卡 `failover_in_progress`。`failover-drill.sh` 已经处理了这一点。

