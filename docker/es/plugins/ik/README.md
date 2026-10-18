# IK 分词器（容器版 ES 用）

`elasticsearch:7.17.10` 官方镜像不含中文分词器，而 `gates/es-shop-mapping.json` 的
`shop_search` 索引用的是 `ik_max_word` / `ik_smart`。缺了它，建索引会直接失败（或者被 `_bulk`
动态建成 `standard` 分词的索引，检索结果悄悄变差且不报错）。

因此把插件文件随仓库带一份，供 `docker/docker-compose-infra-lite.yml` 的 `elasticsearch` 服务
（挂载 `./es/plugins:/usr/share/elasticsearch/plugins`）以及手工起的 `docker run ... elasticsearch:7.17.10`
使用 —— 这样 **全新机器上不装宿主版 ES 也能跑通检索链路**。

| 项 | 值 |
|---|---|
| 来源 | `https://get.infini.cloud/elasticsearch/analysis-ik/7.17.10`（官方 CDN 直链） |
| 版本 | 7.17.10（必须与 ES 大版本一致，否则 ES 启动时报插件版本不匹配） |
| 内容 | `elasticsearch-analysis-ik-7.17.10.jar` + 依赖 jar + `plugin-descriptor.properties` + `config/` 词典 |
| 放入方式 | 解压到本目录（目录名必须是 `ik`，ES 按目录名识别插件） |

升级 ES 大版本时这里必须同步换一版，否则容器起不来。

## 体积说明

本目录约 8MB（插件 jar + 词典），随仓库内置：换来的是全新机器**不装宿主 ES、不联网下载插件**也能跑通检索链路。若改为启动时在线安装（`elasticsearch-plugin install`），则要求网络可达插件源且每次起环境都有外部依赖 —— 演示场景不值得。词典文件（`config/*.dic`，约 8MB 主体）是 IK 发行包原装内容，非项目自定义。
