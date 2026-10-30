# 搜索历史写入改造

搜索结果是主流程，个人搜索历史是旁路。本批只处理正常重复异常和历史数据库故障传播，不引入结果缓存或新的业务状态。

## 行为

- 历史去重依赖现有`uk_user_word(user_id,keyword)`，使用参数绑定的`INSERT ... ON DUPLICATE KEY UPDATE id=id`。同词重搜保留原id和create_time，历史排序与之前一致；不同用户互不合并。
- 不使用`INSERT IGNORE`，非重复数据库错误仍由驱动抛出；service捕获DataAccessException并记录固定标签`llmall.search.history.failure{reason="database"}`，不让成功ES查询触发无意义的MySQL回落，也不影响成功的MySQL搜索结果。
- 不在异常日志写关键词、SQL错误详情或凭据，只记用户id和异常类型。长时间故障仍可能产生较多日志，本批未加入限频器。
- 匿名/空白词不写个人历史；超过64个Unicode字符的词只跳过历史，不截断实际搜索关键词。64个补充平面字符按64计算，避免误用UTF-16长度。
- 仍是同步的一次数据库写入，数据库超时仍可能增加响应时间；故障隔离不等于消除等待。未修改数据库结构，需要沿用现有唯一键和INSERT/UPDATE权限。

## 验证和真实性边界

7项service回归覆盖auto/es/mysql结果、故障旁路、重复写入、匿名/空白与Unicode边界。真实MySQL集成测试`SearchHistoryMysqlTest`使用随机独立schema、10000条历史、最多4连接，测试并发去重、原id/时间不变、用户隔离和严格SQL错误；临时schema保留，不清理业务数据。

三轮DAO微基准消除了6000次正常重复键异常，但未观察到稳定、显著的延迟改善。**不能将此结果写成搜索HTTP avg/P99提升或整站容量提升。** 真实HTTP链路的改后对比仍未完成，本批源码未部署。

## 可选复现（仅独立测试环境）

先在当前PowerShell进程设置`LLMALL_TEST_DB_PASSWORD`（本机3307测试MySQL的root凭据，不提交到文件）和`LLMALL_BENCH_OUTPUT`（新的独立输出目录，重复运行前归档原结果）。该集成用例只连接127.0.0.1:3307，自动创建`llmall_hist_bench_<随机后缀>`，不接触业务schema；没有通用清理功能。

```powershell
mvn -pl llmall-search-service -am test -Dtest=SearchHistoryMysqlTest -Dsurefire.failIfNoSpecifiedTests=false -Dgroups=integration -DexcludedGroups= -DforkCount=0
```

根POM现通过属性设置默认`excludedGroups=integration`，普通`mvn test`仍不连接中间件；此前写死的插件配置会导致上述显式命令运行0项，现已修正。真实验证必须确认该类确实运行1项、无跳过，不能只看BUILD SUCCESS。

遵守主机内存余量要求，串行执行，可用内存不足1GiB时停止；可设置`MAVEN_OPTS=-Xmx192m`。需要默认后台测试集时不设置integration筛选和数据库凭据。本批不重建全栈、不执行升级SQL、不部署其他资金改造，也不更新最终分析/面试手册。
