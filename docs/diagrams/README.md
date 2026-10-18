# 图目录（diagrams）

README 与各文档里的架构图、时序图、流程图，**以本目录的 `.png` 为准**，`.mmd` 是同源的 mermaid 源码。

## 为什么不用 GitHub 原生 mermaid

GitHub 的 mermaid 是页面加载后再去请求 `viewscreen.githubusercontent.com` 渲染的。该域名在部分网络
（含国内多数网络）不可达，此时 GitHub 会退化成显示源码 + `Unable to render rich display`，
读者看到的是一段代码而不是图。**图是给别人看的第一眼**，所以这里改为提交渲染好的图片。

`.mmd` 源码一并入库，是为了图能被审阅、能被修改后重建——不是装饰。

## 文件

| 图 | 用在哪 |
|---|---|
| `01-architecture.png` | README §1.1 分层架构 |
| `02-auth-sequence.png` | README §2.1 统一鉴权与身份透传 |
| `03-pay-consistency.png` | README §2.2 下单支付跨域最终一致性 |
| `04-review-ranking.png` | README §2.3 评价 → 热度 → 榜单 |
| `05-order-state.png` | README §2.4 订单状态机 |
| `06-deploy-topology.png` | README §3 部署形态 |
| `07-gate-pipeline.png` | README §5 八道闸门流水线 |
| `08-service-dependency.png` | `docs/架构设计.md` §2 服务依赖 |
| `09-deploy-topology-detail.png` | `deploy/部署说明.md` §2 部署拓扑 |
| `10-deploy-flow.png` | `deploy/部署说明.md` §3 首次部署流程 |

## 改图与重建

改 `.mmd` 后重渲染（需要 Node 20+；不需要联网，除非首次安装 CLI）：

```bash
# 一次性准备（全局或本地安装均可）
npm install -g @mermaid-js/mermaid-cli

# 渲染单张（宽度 1800、2 倍像素密度，白底，保证在 GitHub 上清晰）
mmdc -i docs/diagrams/01-architecture.mmd -o docs/diagrams/01-architecture.png -b white -w 1800 -s 2
```

无头浏览器默认用 puppeteer 自带的 Chromium；若本机已有 Chrome/Edge，用配置文件指定更省事：

```json
{ "executablePath": "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "args": ["--no-sandbox", "--disable-gpu"] }
```

```bash
mmdc -p /path/to/puppeteer-config.json -i xxx.mmd -o xxx.png -b white -w 1800 -s 2
```

> 图里的文字、连线都改自 `.mmd`，不要把 `.png` 当成唯一来源手工修图——下次重建会被覆盖。
