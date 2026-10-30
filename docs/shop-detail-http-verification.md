# 门店详情 HTTP 验证（2026-10-29）

本轮新增 opt-in 集成类 `ShopDetailHttpBenchmarkTest`，验证已完成的门店缓存改造，不修改业务逻辑。测试通过真实本机 HTTP 进入 ShopController、ShopServiceImpl、ShopDetailCache，连接真实私有 Redis，以及 MyBatis-Plus ShopMapper/ShopTypeMapper 对应的私有 MySQL 库。

## 结果

| 场景 | 请求与状态 | 门店读取开始/完成 | 结论 |
|---|---|---|---|
| 三轮正常热读 | 6000次，全部200 | 0/0，字典SQL也为0 | 合并样本AVG 1.000ms、P99 2.197ms |
| 同门店冷热点 | 8次，全部200 | 1/1 | 一次SQL回填，其他请求得到相同门店 |
| 过期缓存、后台读取受阻 | 8次，全部200 | 1/0（响应完成时） | 全部响应返回明确的旧值，随后才释放数据库重建 |
| 受控调度拒绝 | 8次，全部200 | 0/0 | 返回明确的旧值，锁被释放 |
| 实际Redis停机、同门店回源 | 8次，全部200 | 1/1 | 重叠请求共享一次SQL，未绕过实际ShopMapper |
| 停机且4个不同门店占满回源 | 4次200、1次503 | 4/4（释放后） | 第五个不同门店得到真实HTTP503，不执行额外SQL |

热读各轮4并发、300次顺序预热、2000次测量。各轮AVG/P99/RPS分别约1.099ms/2.476ms/3562、0.960ms/1.619ms/4094、0.942ms/1.447ms/4167；准确值以原始JSON和CSV为准。合并P99直接从6000个原始时延样本重新计算，不对阶段P99取平均。

总计6037测量响应，6036个200、1个预期503。HTTP状态、DTO id、价格换算、真实类型名、旧值名称均在JUnit逐请求断言；错误响应同时验证HTTP503与JSON code503。CSV保存时延/状态，未保存完整响应报文。离线脚本检查样本数量、序号唯一性、状态、AVG/P99和SQL统计；吞吐按阶段墙钟时间计算。

## 环境与范围

- JDK17.0.20、单JVM256MiB堆、`TieredStopAtLevel=1`；HTTP最多8工作线程，MySQL池最多4连接，Redis IO/计算线程各2。
- MySQL夹具包含1000个门店及实际实体所有字段，真实类型字典。热读只访问其中同一个门店，不代表全目录负载或真实业务分布。
- MyBatis本地缓存为STATEMENT、二级缓存关闭。拦截器只统计/暂停实际读取，不伪造结果；分别记录读取开始数和成功完成数，造数SQL不计入阶段数据。
- 私有Redis容器上限48MiB、数据上限16MiB，仅127.0.0.1随机端口。测试校验owner标记，宿主校验runId、完整容器ID和标签后停机，未操作业务Redis。
- Redis命令超时100ms为测试配置。冷读人为暂停100ms、停机回源人为暂停150ms，以构造重叠。非热读只有8次或5次，主要是功能检查，其P99接近最大值，不作性能稳定性/SLA结论。
- 拒绝阶段为受控Executor拒绝；真实线程池饱和/禁止CallerRuns由上轮ShopCacheExecutorTest验证。
- 没有网关、鉴权、Nacos、MQ和完整Spring Boot配置，其他不参与详情读取的依赖使用替身。本轮是组件HTTP实测，没有部署业务服务。
- 没有旧版本对比，不声称降低了某个百分比，不与历史6700RPS网关基线比较。短时间三轮仍有预热/机器波动，不能外推持续吞吐容量。

## 重跑与证据

测试标记为 `integration`，默认测试排除。显式选择此类时设置：

- `LLMALL_CACHE_RUN_ID`：新建私有Redis的owner标记。
- `LLMALL_CACHE_REDIS_PORT`：127.0.0.1上的私有随机端口，拒绝6379。
- `LLMALL_SHOP_HTTP_OUTPUT`：尚不存在的新结果目录。
- `LLMALL_TEST_DB_PASSWORD`：本地3307 MySQL的测试建库凭据，仅置于进程环境，不写文件/日志。

执行 `mvn -pl llmall-shop-service -am test -Dgroups=integration -DexcludedGroups= -Dtest=ShopDetailHttpBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0`，建议堆256MiB并指定本机Maven仓库。测试在输出目录写入`request-outage`后，宿主须校验内容等于runId，只停止所属临时Redis，再将相同runId写入`outage-ready`。60秒无握手则失败，不能指向业务Redis或复用旧结果目录。

本次宿主脚本、最终原始数据、离线核算和报告位于 `D:/codex/llmall-shop-http-20261029/`，其中 `start-redis.ps1`、`stop-redis.ps1`、`run-http.ps1`、`analyze-http.mjs` 可查阅。首轮保留为http-pilot；加强断言后的完整重跑为http-final，不按指标择优。正常/异常结束均尝试独立清理各资源，清理失败会将completed置false并使JUnit失败；判断结果需同时检查JUnit退出状态、completed和cleanupPassed。

最终runId `abbe56fec7ba42e9bb4eb5355a73f033`，私有库 `llmall_shop_http_b68533e911a3` 保留；首轮库 `llmall_shop_http_cedbbb51f54f` 也保留。临时Redis已停止并自动删除。阶段可用内存约2731～2784MiB，仅抽样。源码未部署，最终完整分析/面试手册仍待项目收口分别更新。
