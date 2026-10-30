# 搜索空索引探测合并

## 为什么改

旧实现遇到零命中时探测全索引count，正常命中又清掉探测时间，混合流量会反复count；两个volatile字段只能保证可见性，不能保证冷请求合并。失败回归复现：10次命中/未命中交替带来10次额外count，4个并发冷请求带来4次count。

## 当前策略

`EmptyIndexFallbackProbe`每实例只保存一份不可变布尔快照，不缓存搜索结果：

- 实际非空结果是索引非空的证据，10秒内复用，后续零命中不再重复count。新证据不延长尚鲜活的非空快照，避免每个命中都分配对象。
- 无证据或已过期时，零命中请求在探测锁内双重检查并共用一次探测。只有ES总文档数为0且读模型有数据，auto模式才回落；纯es/mysql选择保持不变。
- 非空结果不等待探测锁，能直接撤销缓存的回落状态。CAS保证在途旧探测不能覆盖更新的非空证据。
- 使用nanoTime单调时钟，TTL从证据/探测完成时计算。探测异常沿用“不据此回落”并短缓存该结论，搜索结果及热词/历史逻辑不变。

## 权衡与边界

这是有界的实例内状态证据，不是强一致性。索引刚清空时，auto回落可能等当前10秒证据过期；一旦实际查询恢复命中，则立即撤销回落。多实例会各探测，不保证整个集群每10秒只一次。慢探测的未命中等待者仍会等待该探测完成，正常命中不受这把锁阻塞。

未处理ES查询本身超时、异步索引一致性、长时故障/回落日志限频。这批不改部署配置、不新增运行依赖、不涉及资金协议；源码同步不等于现有Docker镜像已更新。

## 验证

新增14项service/状态回归覆盖混合流量、并发、TTL边界、时钟零点/回绕、恢复和旧结果晚到等。默认`mvn test`仍排除真实中间件用例。

可选`SearchProbeHttpBenchmarkTest`使用独立ES索引、临时localhost端口和真实MVC/controller/service/ES网关，在正常索引的50%命中/50%未命中流量中对照旧策略复刻与新策略。Redis热词和MySQL用替身，未经过网关/鉴权/Nacos，不属于生产完整HTTP链路；预期未命中为合法结果，不是用空索引刷低延迟。

先设置`LLMALL_PROBE_BENCH_OUTPUT`为尚不存在的新输出目录，再运行（需要本机9200真实ES）：

```powershell
mvn -pl llmall-search-service -am test -Dtest=SearchProbeHttpBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false -Dgroups=integration -DexcludedGroups= -DforkCount=0
```

正式对照采用`MAVEN_OPTS=-Xmx256m -XX:TieredStopAtLevel=1`。造数/循环期间定期检查可用内存>=1GiB，4个工作线程、ES连接最多4；不要并行启动JMeter或其他大堆任务。只创建随机`llmall_probe_bench_<后缀>`索引，不写业务`shop_search`，不自动删除fixture。脚本保留完成阶段原始时延和整体失败原因，失败阶段没有完整统计。

本次10000文档/4并发/三轮组件对照：ES search各12000次，额外count5991→0；阶段avg中位数2.841→2.365ms、P99中位数4.710→4.467ms。它们只是指定短时组件场景的观测值，不能直接写成整站、任意中文查询或生产SLA。
