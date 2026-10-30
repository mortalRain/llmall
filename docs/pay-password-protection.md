# 支付口令错误保护

本项目定位为个人简历和压测项目，本批只补基本错误保护，不扩展支付业务。

- 每连续输错5次，锁定5分钟；解锁后重新计数，不增加惩罚时长。锁定期间请求不累计、不续期。正确验证清零，未达阈值的错误记录6分钟滚动过期。
- 旧余额扣款、新支付/普通券持久化扣款、修改支付口令共用同一账户错误预算。登录密码修改使用独立用途key，不能清掉支付错误状态。
- Lua将检查、错误计数与设锁、成功清零做成原子动作；SUCCESS遇到并发新锁时拒绝，不删除锁。
- Redis错误或无效结果时拒绝受保护的口令校验；状态查询也报不可用，不伪报未锁定。退款、撤销和后台补偿不依赖此保护。
- 沿用`auth:pwdfail:<userId>`、`auth:pwdlck:<userId>`；旧锁按已有TTL自然到期，新产生的锁均为300秒。历史永久锁仍被尊重，不会自动删除。登录改密在前缀后增加`login-change:`隔离。

新组件随user-service部署，无数据库迁移。这里只交付源码，未部署到当前Docker服务。脚本用既有两key，适用于当前Standalone/Sentinel；迁往Redis Cluster前需单独处理hash-tag和旧key迁移。Redis淘汰/丢失安全key的部署问题未在本批解决。

Java回归包括拒绝后不写资金账本、并发锁保护、Redis异常拒绝和取消退款独立性。真实Lua验证命令（不连接已有Redis）：

```powershell
pwsh -NoProfile -File scripts/tests/pay-password-lock.Tests.ps1 -RedisServer <本机redis-server.exe>
```

脚本临时实例绑定127.0.0.1随机端口，关闭持久化，数据内存上限16MiB，并发工作线程最多4个，完成后停止自己创建的进程。MSYS版Redis自动转换路径。不启动Docker容器，不清理业务数据；16MiB是Redis数据上限，进程总内存另有开销。
