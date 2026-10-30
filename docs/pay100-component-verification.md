# 100 并发 HTTP/MySQL 组件验证

2026-10-29。最终原始证据在 `D:/codex/llmall-resume-target-20261029/concurrency/final/`：requests.csv、results.json、checked-summary.json、db-audit.tsv、process.log。复核工具为 benchmark/pay100/analyze.mjs；实际运行源码为 Pay100.java，SHA256 `28C5E7127DCA7F7AD76154240D33A31D893CFFC89F03D1AED7C93F285727941A`。

## 结果

- 100 客户端线程用 ready/start 屏障同时开始，100 个独立合成账号各跑15次，完成1500组“下单→申请支付单→余额支付”，4500个主流程HTTP请求全部200。
- 客户端原始请求时间线、服务端拦截器分别观测到在途峰值100，不只把线程池上限当成实际并发。
- MySQL独立只读核对：已支付业务订单、支付单、成功尝试、扣款记录、余额流水分别1500；总余额10,000,000→9,550,000分，差450,000分=1500×300。没有退款记录。
- 三轮分别对同一支付单发100请求，每轮1个200、99个400业务拒绝；真实余额每轮100,000→99,700分，成功尝试和扣款流水各1条，订单支付回调终态也已断言。
- 合计4815个HTTP响应：4500主流程、9预热、6竞争准备、300竞争请求。297个400是预期竞争拒绝，不写全场景HTTP零错误；独立CSV校验无其他非预期状态。

## 范围与资源

真实Tomcat/Spring MVC订单及支付控制器，当前OrderServiceImpl、PayOrderServiceImpl、BalancePaymentCoordinator、PaymentAttemptStore、BalancePaymentService及MyBatis/MySQL。支付尝试与余额使用Spring事务代理，订单创建使用原TransactionTemplate，支付回调使用事务代理。多个域在单JVM、同一随机私有schema中分别执行本地事务，不是部署后的独立微服务数据库拓扑。

身份来自仅本机测试头；RPC用进程内适配器；库存为合成SQL适配器；密码防错Redis守卫为替身，BCrypt cost=4；消息使用同步投递/确认适配器，没有真实RabbitMQ。这些不能代表网关JWT、真实跨服务网络/消息故障、默认密码成本或生产吞吐。它证明指定组件与事务在100并发下的正常流程及同单单次扣款，不证明所有故障下端到端一致性。

单JVM堆256MiB/线程栈512KiB，DB池8，服务端最大线程100。正式运行抽样空闲内存4725–5043MiB（不是全程峰值监控），结束宿主独立抽样4954MiB；Docker只运行MySQL。客户端池、数据库池关闭且HTTP端口不再监听。私有schema `llmall_pay100_3b58b5fa7811` 和Tomcat临时目录保留用于核对，不将cleanupPassed解释为删库/目录。

## 重跑

先在同一源码副本完成默认 Maven 回归以生成各模块 classes 与 Surefire classpath。再运行：

```powershell
./benchmark/pay100/run.ps1 -RepoRoot D:/codex/llmall-resume-target-20261029/work -OutputDirectory D:/codex/pay100-new-run
```

需要JDK17、Node、MySQL8容器 `mysql` 映射127.0.0.1:3307。凭据只从现有容器传到测试进程环境，脚本不打印；每次新随机schema，绝不对业务库运行64/65/66。已有本地H2离线测试适配不属于项目交付依赖。

首次预热分别暴露端口和夹具依赖/序列化配置问题，均未当成成功成果；pilot4完整通过后，加强CSV序号、回调事务及资源关闭断言，正式final再跑通过，未择优挑延迟。本验证不再对外引用组件吞吐或模拟条件下的低延迟当SLA。
