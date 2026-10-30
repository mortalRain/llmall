# 用户服务启动失败的恢复记录与步骤

## 本次验证（2026-10-28，Asia/Shanghai）

现象：Docker Desktop启动现有服务后，`llmall-user-service`进程存活但健康为unhealthy，网关登录返回503，搜索/商品分页仍可访问。

本次日志显示：

- 用户服务21:14:25.184启动Tomcat，21:14:25.605报Nacos注册失败，21:14:28.608报`Application run failed`，Nacos客户端状态为`STARTING`。
- Nacos在同一秒的21:14:25.375才报告启动完成。两个容器几乎同时启动；失败后用户Java进程仍存活，`restart: unless-stopped`没有因为unhealthy自动重启它。
- 确认Nacos当前readiness返回200/OK后，只重启现有用户服务；随后用户服务healthy，既有演示账号登录200，`GET /follows/feed?size=10`返回200。

以上证据支持启动时序/客户端连接未就绪这一诊断；HTTP就绪并不证明客户端gRPC通道已经连接。本次没有修改Nacos认证、服务配置或应用代码。

## 安全恢复步骤

1. 只读检查状态与最近日志，避免把历史故障当本次故障：

```powershell
docker inspect --format '{{.State.Health.Status}}' llmall-user-service
docker logs --since 5m --tail 100 llmall-user-service
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8848/nacos/v1/console/health/readiness
```

2. 仅当日志/健康表明用户服务启动失败、Nacos已就绪且主机内存有余量时，重启这个现有容器：

```powershell
docker restart llmall-user-service
```

3. 等待其健康变为healthy，再通过既有前端验证登录和Feed；持续失败时停止盲目重启，保存本次日志继续定位。不要用重建全栈、删卷、改密码或放宽鉴权解决注册失败。

日志可能含连接信息，分享前脱敏；不要输出容器完整环境变量、登录口令或令牌。

## 本次边界

- 重启前后用户服务镜像ID一致，为`sha256:2ca14a08586f9910e69aee87a6b0a9ebcb35e9222c50aaacd60b0297e389d926`，内存限制保持256MiB；没有新建容器、重建镜像、执行迁移或业务扣退款。
- 前几批普通券/支付安全新源码仍未部署，不能把这次恢复称为新版本上线。
- Feed虽返回200，但当前演示账号列表为空，不能用它宣称正常业务数据量下的性能。下一轮先准备有明确归属的独立测试数据，再做Feed性能优化。
- 这是一次运行恢复和可复现排障步骤，**不是永久自动启动修复**。Docker Desktop再次同时拉起全部容器仍可能发生同类问题。持久方案应后续验证基础设施就绪门禁/有界注册重试，以及启动失败后退出进程的策略；不能只依赖容器unhealthy状态触发restart。
- 完整分析与面试手册仍在全部整改结束后另行更新。
