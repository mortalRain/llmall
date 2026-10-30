# 读接口压测结果校验

适用于当前固定基线：搜索/商品分页，2轮，每轮1/4/8并发，共12档。结果目录需包含`results.json`和`requests.csv`，字段由本轮read-baseline.mjs产生。

```powershell
node --max-old-space-size=32 benchmark/check-read-results.mjs <结果目录>
node --max-old-space-size=32 --test --test-concurrency=1 benchmark/check-read-results.test.mjs
```

不发网络请求，不连接数据库或Docker，不覆盖原始测量文件。只生成`checked-analysis.json`与`checked-report.md`；复测前归档结果目录。

- 校验CSV结构、有限非负延迟、样本顺序、HTTP状态/成功标记、档位归属，以及请求数/失败数/可选HTTP状态汇总。
- 只有runner确认完成、12档齐全非空、结束时间有效且无中止/运行错误时，`completed=true`。不完整或守卫中止会生成未完成报告，退出码2。
- 成功延迟与有效吞吐只算有效HTTP200样本；无成功样本时延迟为null。`errorFree`与`completed`分别表达零错误与流程完成，两者都不是性能SLA结论。
- 数据损坏时退出码1，并将旧checked结果标记为无效，防止沿用上次成功报告。校验正常且完整时退出码0，即使存在已记录的失败请求；自动化需单独判断`errorFree`和业务目标。

仅验证样本与汇总一致性，不能证明请求实际发生、响应业务正确、资源采样准确或具备大规模性能。原始valid标记仍由负载工具验证；镜像、数据量、资源条件和限流原因需结合原报告。

本工具不替代压测，也不修改已经出具的历史REPORT.md。后续负载计划变化时应显式升级校验计划及用例，不能直接用本工具判定其他业务负载。
