# LLMall 前端 · 同城生活

Vue 3 + TypeScript + Vite + Pinia + Vue Router 4 + Element Plus + Axios 的浅蓝主题企业级演示前端。

旧版单文件演示台（`index.html` + `theme.css`）已原样保留在 [`legacy-demo/`](./legacy-demo/)，不再参与构建。

## 快速开始

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
```

开发服务器把 `/api` 代理到 `http://localhost:8080`，并去掉 `/api` 前缀（与网关路由一致）。
如果网关不在默认地址，启动前设置 `LLMALL_API_TARGET`：

```bash
LLMALL_API_TARGET=http://192.168.1.10:8080 npm run dev
```

生产构建与本地预览：

```bash
npm run build      # vue-tsc -b && vite build，产物在 dist/
npm run preview    # http://localhost:4173，同样代理 /api
```

演示账号：**Jack / 123456**；演示支付口令：**888888**（与登录密码不同）。

## 启动与联调

### 1. 前置条件

- Node.js **20.19+**（建议 22.x）与 npm 10+
- 后端网关可访问：默认 `http://localhost:8080`
- 至少启动网关与用到的业务服务；本仓库 Docker 环境的网关服务名为 `gateway`

### 2. 安装与启动

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`，使用演示账号登录：

| 项目 | 值 |
| --- | --- |
| 登录账号 | `Jack` |
| 登录密码 | `123456` |
| 支付口令 | `888888`（与登录密码不同，收银台/付费券会用到） |

### 3. `/api` 代理

开发服务器与 `vite preview` 都会把 `/api` 代理到 `http://localhost:8080`，并去掉 `/api` 前缀：

```text
浏览器 /api/users/login  ->  Vite proxy  ->  http://localhost:8080/users/login
```

网关不在默认地址时，用 `LLMALL_API_TARGET` 覆盖：

```bash
LLMALL_API_TARGET=http://192.168.1.10:8080 npm run dev
```

### 4. 鉴权头说明（VITE_AUTH_SCHEME）

当前仓库网关按**裸 JWT**读取 `authorization`（不剥离 `Bearer ` 前缀），因此前端默认发送裸 token：

```bash
# 默认行为，等价于 VITE_AUTH_SCHEME=raw
npm run dev
```

如果网关后续升级为标准的 `Bearer <token>` 方案，无需改代码：

```bash
VITE_AUTH_SCHEME=bearer npm run dev
VITE_AUTH_SCHEME=bearer npm run build
```

> 401 处理：并发请求共享同一个刷新 Promise，只调用一次 `POST /users/token/refresh`
> （`X-Refresh-Token` 请求头），成功后就地重放原请求；失败则清空 localStorage 并跳转 `/login`。

### 5. 构建、预览与 Docker

```bash
npm run build      # vue-tsc -b && vite build，产物在 dist/
npm run preview    # http://localhost:4173，同样代理 /api
```

容器构建（多阶段 Node 22 构建 + Nginx 托管，不修改 deploy 下的 compose）：

```bash
# 在仓库根目录（llmall/）执行
docker build -f frontend/Dockerfile -t llmall-frontend:1.0.0 frontend

# 运行时需接入 gateway 所在的 Docker 网络（本项目 external network 为 local-life）
docker run --rm -p 8081:80 --network local-life llmall-frontend:1.0.0
```

容器内 Nginx 的 `/api/` 反向代理到 `http://gateway:8080/`，与开发环境的 `/api` 语义一致；
因此前端容器必须和 `gateway` 处于同一 Docker 网络。

## 目录结构

```
frontend/
├─ index.html
├─ vite.config.ts          # 5173 dev server + /api 代理
├─ Dockerfile              # Node 22 构建 + Nginx 运行
├─ nginx.conf              # SPA 回退 + /api 反代 gateway:8080
├─ .dockerignore
├─ tsconfig*.json
├─ src/
│  ├─ main.ts              # Element Plus 全量引入 + 中文 locale
│  ├─ App.vue
│  ├─ style.css            # 设计变量、Element Plus 主题覆盖、响应式
│  ├─ api/
│  │  ├─ http.ts           # axios 实例、鉴权头、401 单次刷新队列
│  │  ├─ modules.ts        # 按业务域拆分的接口
│  │  └─ types.ts          # 后端 VO/DTO 对应的简单接口
│  ├─ stores/auth.ts       # 登录态 + 用户信息（localStorage + Pinia）
│  ├─ router/index.ts      # 路由与登录守卫
│  ├─ layouts/AppLayout.vue
│  ├─ components/          # PageHeader / StateBlock / ShopCard / FeedCard / VoucherCard
│  └─ views/
│     ├─ Login.vue
│     ├─ Feed.vue
│     ├─ Shops.vue         # 门店列表（/shops/page 分页）
│     ├─ ShopDetail.vue
│     ├─ Checkout.vue
│     ├─ Orders.vue        # 顾客订单：核销码 + 到店出示（无核销按钮）
│     ├─ StaffVerify.vue   # 员工核销台（/staff/verify，仅门店员工可用）
│     └─ Profile.vue
├─ e2e/                    # Playwright 冒烟（地址 / 核销 / 付费券）
├─ playwright.config.ts
└─ legacy-demo/            # 旧单文件演示台，仅留档
```

## 页面与接口

| 页面 | 接口 |
| --- | --- |
| 登录 | `POST /users/login` |
| 关注动态 | `GET /follows/feed/v2?size=10`，复合游标 `li/ct/ci/ai` |
| 门店列表 | `GET /shops/page?page=&size=&keyword=` |
| 门店详情 | `GET /shops/{id}`、`GET /items/shop/{shopId}`、`GET /vouchers/list?shopId=` |
| 领券 | `POST /vouchers/{id}/receive`（付费券在请求体带 `pw`） |
| 下单 | `GET /addresses`、`POST /orders`、`GET /orders/{id}` |
| 支付 | `POST /pay-orders`、`POST /pay-orders/{id}` |
| 订单（顾客） | `GET /orders`、`GET /orders/{id}`、`PUT /orders/{id}/cancel` |
| 核销台（员工） | `GET /shops/my/staff`（判断员工身份/任职门店）、`POST /orders/verify` |
| 我的 | `GET /users/me`、`GET /vouchers/my`、`POST /users/logout` |

> **核销语义**：核销是门店员工的动作。顾客侧只展示核销码并提示"到店出示给店员"，没有核销按钮；
> 核销动作在 `/staff/verify` 核销台完成，入口只对 `GET /shops/my/staff` 非空的用户展示。
> 前端判断只是 UX，后端对非本店员工始终返回 403。

字段口径以后端代码为准：

- 订单侧余额支付是 `paymentType=3`；支付侧是 `PayType.BALANCE=5`，两个枚举不同。
- `PayApplyDTO` 需要 `bizOrderNo / amount / payChannelCode / payType / orderInfo`。
- `PayOrderFormDTO` 只需 `pw`，支付单 id 走路径。
- 核销 `OrderVerifyDTO` 需要 `verifyCode + shopId`。
- 所有 JSON 响应统一由 `json-bigint` 解析（`storeAsString: true`）：超过 JS 安全整数范围的 Long id 会自动变成字符串。
  即使后端个别接口把 19 位雪花号作为 JSON number 返回，也不会被 `JSON.parse` 舍入；`types.ts` 里所有 id 字段均为 `string`。

## 鉴权说明（与常见示例的差异）

`http.ts` 默认在 `authorization` 中发送**裸 JWT**，而不是 `Bearer <token>`。
原因：当前仓库的网关 `AuthGlobalFilter#firstHeader` 按裸 JWT 读取，未剥离 `Bearer ` 前缀；实测发送 `Bearer` 会得到 401 `invalid-token`，后端文档与旧演示台也都使用裸 token。

如果网关后续升级为标准的 `Bearer` 方案，不需要改代码，构建时设置即可：

```bash
VITE_AUTH_SCHEME=bearer npm run build
```

401 处理：所有并发 401 共享一个刷新 Promise，只调用一次
`POST /users/token/refresh`（`X-Refresh-Token` 请求头），刷新成功后重放原请求；
刷新失败清空本地登录态并跳转 `/login`。

## 交付验收

```bash
cd frontend
npm install
npm run build
npm run dev
curl -I http://localhost:5173

# Playwright 冒烟（3 条：新增地址 / 顾客出示核销码+员工核销台 / 付费券）
npm run test:e2e
```

- `npm run test:e2e` 用本机 Chrome（`channel: 'chrome'`），`baseURL=http://localhost:5173`，
  `testDir=e2e`，`webServer` 在 dev server 未启动时自动执行 `npm run dev`（已启动则复用）。
- 前置：网关 8080 与 MySQL/Redis/Nacos/RabbitMQ 已运行、演示数据已导入（`sql/merged/README.md`）。
- 用例可重复执行：地址/订单每轮新建；付费券"一人一张"，已领取时断言"已领取"而不重复扣款。

构建产物约 1.7 MB（未压缩），Element Plus 全量引入后主 vendor chunk 约 823 KB；
已在 1440px 与 375px 宽度下检查布局与主要交互。
