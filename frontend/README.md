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

## 目录结构

```
frontend/
├─ index.html
├─ vite.config.ts          # 5173 dev server + /api 代理
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
│     ├─ Orders.vue
│     └─ Profile.vue
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
| 订单 | `GET /orders`、`GET /orders/{id}`、`PUT /orders/{id}/cancel`、`POST /orders/verify` |
| 我的 | `GET /users/me`、`GET /vouchers/my`、`POST /users/logout` |

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
```

构建产物约 1.7 MB（未压缩），Element Plus 全量引入后主 vendor chunk 约 823 KB；
已在 1440px 与 375px 宽度下检查布局与主要交互。
