# M0 · 环境搭建说明（Seata + ES + Kibana）

> 目标：在你现有 VM（`192.168.189.128`，Docker 里已有 MySQL/Nacos/RabbitMQ/Redis）上补齐 **Seata** 与 **Elasticsearch**，并让 11 个服务后续能连上。
> 验收标准：`curl http://192.168.189.128:9200` 返回集群信息；Kibana 可访问；**Nacos 服务列表里能看到 seata-server**。

---

## 1. VM 扩容与内核参数（最先做，最容易漏）

1. **内存调到 ≥ 8G（建议 10-12G）**：VMware → 虚拟机设置 → 内存。
   预算：MySQL 1G + Nacos 1G + RabbitMQ 512M + Redis 256M + Seata 512M + ES 1.5G + Kibana 1G ≈ **6G**。
   > ES 和 Nacos 抢内存会导致服务随机掉线，这是最浪费时间的坑，务必先调。
2. **内核参数**（ES 必须）：
   ```bash
   sudo sysctl -w vm.max_map_count=262144
   echo "vm.max_map_count=262144" | sudo tee -a /etc/sysctl.conf   # 持久化
   ```
3. **做一次 VM 快照**（环境坏了能回滚，这是你的保命符）。

## 2. 容器网络（已统一，不用再挑网络名）

三份 compose（`docker-compose-infra-lite.yml`、`docker-compose-addon.yml`、`llmall/deploy/docker-compose-apps.yml`）
现在统一接入**同一个 external 网络 `local-life`**，容器之间才能按容器名互访
（应用容器里的 `LLMALL_DB_HOST=mysql`、`LLMALL_ES_HOST=elasticsearch` 靠的就是它）：

```bash
docker network create local-life     # 只需建一次
```

`docker/.env` 里只需保留 VM 地址（网络名保持一致，需要时再覆盖）：
```properties
APP_NETWORK=local-life
VM_IP=192.168.189.128
```
> 互通验证：`docker exec -it seata-server ping nacos`。
>
> 旧写法是各自 `docker network ls` 找一个网络名填 `INFRA_NETWORK`（默认还兜底成 `bridge`），
> 结果很容易"infra 在一个网络、apps 在另一个网络" → 应用容器报 `UnknownHostException: mysql`。
> 三份文件现在都用同一个 `name: ${APP_NETWORK:-local-life}`，这种错配从源头上没了。

## 3. Seata Server 配置（1.4.2）

### 3.1 数据库准备

Seata 用 **db 模式**存会话，需要独立的 `seata` 库：

```bash
# 建库
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS seata DEFAULT CHARACTER SET utf8mb4;"
```

三张表的建表语句**直接用官方发行包里的脚本**，不要手写（避免版本差异）：
> Seata 1.5.2 发行包 → `script/server/db/mysql.sql` → 在 `seata` 库里执行
> （**1.5.2 是 4 张表**：`global_table`、`branch_table`、`lock_table`、`distributed_lock`，
> 最后一张还要插 4 行种子；镜像里不含 script 目录，脚本已收进 `../../sql/merged/03-seata-server.sql`。
> 从 1.4.2 升级过来的库改用 `03b-seata-server-1.5.2-upgrade.sql`）

### 3.2 每个业务库都要建 `undo_log`（AT 模式必需）

对 `llmall-user / llmall-shop / llmall-item / llmall-cart / llmall-order / llmall-pay / llmall-promotion / llmall-content` **每个库**都执行：

```sql
CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id`     BIGINT       NOT NULL COMMENT 'branch transaction id',
  `xid`           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
  `context`       VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` LONGBLOB     NOT NULL COMMENT 'rollback info',
  `log_status`    INT          NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created`   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  `log_modified`  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';
```

> 我后续会在 `sql/merged/` 里生成一份 `all-undo-log.sql`，一次给所有库建好。

### 3.3 `registry.conf` / `file.conf`

放到 `docker/seata/` 下（compose 已挂载）。关键内容：

`registry.conf`：注册中心与配置中心都指向你的 Nacos
```hocon
registry {
  type = "nacos"
  nacos {
    application = "seata-server"
    serverAddr = "192.168.189.128:8848"
    group = "SEATA_GROUP"
    namespace = ""
    cluster = "default"
  }
}
config {
  type = "nacos"
  nacos {
    serverAddr = "192.168.189.128:8848"
    group = "SEATA_GROUP"
    namespace = ""
    dataId = "seataServer.properties"
  }
}
```
`file.conf`：`store.mode = "db"`，并填写 `seata` 库的 JDBC 连接（driver `com.mysql.cj.jdbc.Driver`，url 加 `useSSL=false&serverTimezone=Asia/Shanghai`）。

> 配置中心的 `seataServer.properties` 可从发行包 `script/config-center/config.txt` + `nacos-config.sh` 一键导入。

## 4. Elasticsearch 7.17.10 + IK 分词器

1. 启动 ES（compose 已配好）：`docker compose -f docker-compose-addon.yml up -d elasticsearch`
2. **装 IK 分词器**（中文分词必需）：
   ```bash
   # 版本必须与 ES 一致：7.17.10
   wget https://github.com/infinilabs/analysis-ik/releases/download/v7.17.10/elasticsearch-analysis-ik-7.17.10.zip
   mkdir -p docker/es/plugins/ik
   unzip elasticsearch-analysis-ik-7.17.10.zip -d docker/es/plugins/ik
   docker restart es
   ```
3. 验证：
   ```bash
   curl http://192.168.189.128:9200
   curl -X POST "http://192.168.189.128:9200/_analyze" -H 'Content-Type: application/json' \
     -d '{"analyzer":"ik_max_word","text":"望京小腰烧烤店"}'
   ```
   能分出中文词就成功。

## 5. 验收清单

- [ ] `docker ps` 里 seata-server / es / kibana 都是 Up
- [ ] Nacos 控制台 → 服务列表 → 能看到 `seata-server`
- [ ] `curl :9200` 返回版本 7.17.10；`_analyze` 中文分词正常
- [ ] Kibana `:5601` 能打开、能连上 ES
- [ ] 每个业务库都有 `undo_log` 表；`seata` 库有 3 张全局表
- [ ] VM 已做快照

## 6. 踩坑提醒

| 现象 | 原因 / 解决 |
|---|---|
| ES 容器反复重启 | VM 内存不足或 `vm.max_map_count` 太小 → 见第 1 节 |
| Seata 起不来 / Nacos 里看不到 | 网络不通（不在同一 docker network）或 `SEATA_IP` 填成了容器内网 IP → 填 VM 的宿主 IP |
| 客户端连不上 Seata | 客户端 `seata.registry`/`service.vgroupMapping` 与 server 的 group 不一致 |
| 中文搜不到 | IK 没装或版本与 ES 不一致 → `_analyze` 先验证 |
| MySQL 8 时区报错 | JDBC url 加 `serverTimezone=Asia/Shanghai` |

---

完成后告诉我结果，我接着做 **M1**：父 pom 版本统一、11 个模块骨架、`bootstrap.yaml` 模板、Nacos 配置清单，以及 `sql/merged/` 全套库表 DDL。
