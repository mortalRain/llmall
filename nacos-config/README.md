# Nacos 共享配置导入说明

这些文件是**新建 4 个服务（shop / promotion / content / search）依赖的共享配置模板**。
导入方式：Nacos 控制台 → 配置管理 → 配置列表 → `+` 新建，**Data ID 用文件名（含 .yaml）**，Group 用 `DEFAULT_GROUP`，格式选 `YAML`，把文件内容粘进去。

| Data ID | 作用 | 备注 |
|---|---|---|
| `shared-jdbc.yaml` | 数据源，库名用 `${llmall.db.database}` 占位 | 你 Nacos 里**应该已有**，比对一下是否一致，别覆盖错的 |
| `shared-mybatis.yaml` | MyBatis-Plus 全局配置 | 新增 |
| `shared-redis.yaml` | Redis 连接（lettuce 连接池） | 新增 |
| `shared-mq.yaml` | RabbitMQ 连接与消费参数 | 新增 |
| `shared-es.yaml` | Elasticsearch 连接 | 新增（M6 用到，提前建好不影响启动） |
| `shared-log.yaml` / `shared-swagger.yaml` | 日志与接口文档 | 你 Nacos 里**已有**，沿用 |

## 占位符约定（重要）

共享配置里一律使用占位符，**具体值由各服务的 `application-local.yaml` 提供**：

| 占位符 | 含义 | local 值 |
|---|---|---|
| `${llmall.db.host}` `${llmall.db.pw}` `${llmall.db.database}` | 数据库 | <vm-ip> / 123 / 各服务自己的库名 |
| `${llmall.redis.host}` `${llmall.redis.pw}` | Redis | <vm-ip> / **123456** |
| `${llmall.mq.host}` | RabbitMQ | <vm-ip> |
| `${llmall.es.host}` | Elasticsearch | <vm-ip> |

> ⚠️ Redis 口令是 **123456**，不是 123（数据库才是 123）。这是实测值：
> 8 份 `application-local.yaml` 与 `llmall-gateway/application.yaml` 里的默认值都是 `${LLMALL_REDIS_PW:123456}`。
> 本文档此前写成 123，照着建环境会以"Redis 连接失败 / NOAUTH"启动失败。

这样做的意义：**换环境（本地 VM → 云服务器）只改一份 `application-dev.yaml`，不用动 Nacos**。
值得注意的一点：配置分层（启动参数 → 服务私有配置 → Nacos 共享配置 → 默认值）。

## 注意

- `shared-jdbc.yaml` 里的 `password` 用的是 `${llmall.db.pw}`，请确认与你现有 Nacos 配置一致；若你现有的是硬编码 `123`，**把新服务也改成硬编码也行**，只要 4 个新服务能连上库即可。
- ES 相关配置在 M6 才会被真正使用；现在建好不会影响启动（Spring Boot 的 ES 自动配置是懒连接）。
- 若 Nacos 里已有 `shared-log.yaml`、`shared-swagger.yaml`，**不要重复新建**，直接沿用。
