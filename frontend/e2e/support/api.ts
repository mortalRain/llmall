import { expect, type APIRequestContext } from '@playwright/test'
import JSONBig from 'json-bigint'

/**
 * E2E 的接口工具层：登录、造地址、造"已支付订单"，以及大整数安全的响应解析。
 *
 * 【为什么需要 json-bigint】后端部分接口（实测 POST /pay-orders 申请支付单）返回的是**裸 JSON number**
 * 的 19 位雪花 id。浏览器端由 frontend/src/api/http.ts 的 json-bigint 兜住；Playwright 的
 * response.json() 走原生 JSON.parse，会把 2114683497881337859 舍入成 2114683497881337900，
 * 再拿它去支付就是"支付单不存在"。所以这里统一用与前端一致的解析器。
 */

const parseBig = JSONBig({ storeAsString: true })

export const DEMO = {
  username: 'Jack',
  password: '123456',
  payPassword: '888888',
  /** 演示账号是 10 号店的 OWNER（sql/merged/32-shop-staff.sql + 33-shop-staff-roles.sql） */
  staffShopId: '10',
  /** 演示账号**不是**这家店的员工，用于断言"非员工核销被 403 拒绝" */
  nonStaffShopId: '11',
  /** 真实非员工账号：复用演示库已有用户 Rose，口令对齐 Jack 的 bcrypt（明文 123456） */
  nonStaffUsername: 'Rose',
  nonStaffPassword: '123456',
} as const

export interface LoginSession {
  accessToken: string
  refreshToken: string
  userId: string
  username: string
  balance: number
}

export interface ShopItem {
  id: string
  shopId: string
  name: string
  price: number
  stock: number
  type: number
}

export interface VoucherSummary {
  id: string
  shopId: string
  title: string
  payValue: string | number
  actualValue: string | number
  received: boolean
  status: number
}

export interface PaidOrder {
  orderId: string
  verifyCode: string
  totalFee: number
  shopId: string
  itemName: string
}

/** 读响应体：统一用 json-bigint（19 位 id 保真），非 JSON 返回原文 */
export async function readJson<T = unknown>(response: { text(): Promise<string> }): Promise<T> {
  const text = await response.text()
  try {
    return parseBig.parse(text) as T
  } catch {
    return text as unknown as T
  }
}

export function authHeaders(token: string): Record<string, string> {
  return { authorization: token, 'content-type': 'application/json' }
}

/** 登录并返回令牌（请求走 Vite 的 /api 代理，与浏览器同一条链路） */
export async function apiLogin(request: APIRequestContext): Promise<LoginSession> {
  return apiLoginAs(request, DEMO.username, DEMO.password)
}

/** 指定账号登录（Rose 等非员工账号的接口造数/断言用） */
export async function apiLoginAs(
  request: APIRequestContext,
  username: string,
  password: string,
): Promise<LoginSession> {
  const response = await request.post('/api/users/login', {
    data: { username, password },
  })
  const text = await response.text()
  expect(response.ok(), `${username} 登录失败 HTTP ${response.status()}：${text}`).toBeTruthy()
  const session = parseBig.parse(text) as LoginSession
  expect(session.accessToken, `${username} 登录响应缺少 accessToken`).toBeTruthy()
  return session
}

/** 每次运行都新建一家地址，避免"地址已存在"导致断言命中旧数据 */
export async function createAddress(
  request: APIRequestContext,
  token: string,
  contact: string,
  street: string,
): Promise<string> {
  const response = await request.post('/api/addresses', {
    headers: authHeaders(token),
    data: {
      province: '浙江省',
      city: '杭州市',
      town: '西湖区',
      contact,
      mobile: '13800001111',
      street,
      isDefault: 1,
    },
  })
  const text = await response.text()
  expect(response.ok(), `新增地址失败 HTTP ${response.status()}：${text}`).toBeTruthy()
  return String(parseBig.parse(text))
}

/** 取该门店最便宜的套餐（可重复跑：库存不足时换下一个候选） */
export async function pickCheapestPackage(
  request: APIRequestContext,
  shopId: string,
): Promise<ShopItem> {
  const response = await request.get(`/api/items/shop/${shopId}`)
  const text = await response.text()
  expect(response.ok(), `查询门店 ${shopId} 商品失败 HTTP ${response.status()}：${text}`).toBeTruthy()
  const items = parseBig.parse(text) as ShopItem[]
  const candidates = items
    .filter((item) => Number(item.type) === 2 && Number(item.stock) > 0)
    .sort((a, b) => Number(a.price) - Number(b.price))
  expect(candidates.length, `门店 ${shopId} 没有可售套餐`).toBeGreaterThan(0)
  return candidates[0]
}

/**
 * 通过接口创建并支付一笔门店套餐订单（等价于前端"确认订单 → 余额支付"两步）。
 *
 * 【为什么用接口造数】核销用例的重点是"谁能核销"，不是"怎么下单"；下单/支付 UI 已有别的用例覆盖。
 * 支付成功是异步（支付服务 → MQ → 交易域），所以最后轮询订单直到 status=2。
 */
export async function createPaidOrder(
  request: APIRequestContext,
  token: string,
  params: { item: ShopItem; addressId: string },
): Promise<PaidOrder> {
  const clientToken = `e2e-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  const createResponse = await request.post('/api/orders', {
    headers: authHeaders(token),
    data: {
      addressId: params.addressId,
      paymentType: 3,
      details: [{ itemId: params.item.id, num: 1 }],
      clientToken,
    },
  })
  const createText = await createResponse.text()
  expect(createResponse.ok(), `创建订单失败 HTTP ${createResponse.status()}：${createText}`).toBeTruthy()
  const orderId = String(parseBig.parse(createText))

  const detailResponse = await request.get(`/api/orders/${orderId}`, { headers: authHeaders(token) })
  expect(detailResponse.ok(), '查询订单详情失败').toBeTruthy()
  const order = parseBig.parse(await detailResponse.text()) as {
    totalFee: number
    shopId: string
    details?: Array<{ name: string }>
  }

  // 支付单申请：金额必须与订单 total_fee 完全一致（单位：分）
  const applyResponse = await request.post('/api/pay-orders', {
    headers: authHeaders(token),
    data: {
      bizOrderNo: orderId,
      amount: Number(order.totalFee),
      payChannelCode: 'balance',
      payType: 5,
      orderInfo: order.details?.map((detail) => detail.name).join(';') || 'E2E',
    },
  })
  const applyText = await applyResponse.text()
  expect(applyResponse.ok(), `申请支付单失败 HTTP ${applyResponse.status()}：${applyText}`).toBeTruthy()
  const payOrderId = String(parseBig.parse(applyText))

  const payResponse = await request.post(`/api/pay-orders/${payOrderId}`, {
    headers: authHeaders(token),
    data: { pw: DEMO.payPassword },
  })
  const payText = await payResponse.text()
  expect(payResponse.ok(), `余额支付失败 HTTP ${payResponse.status()}：${payText}`).toBeTruthy()

  let verifyCode = ''
  let status = 1
  for (let attempt = 0; attempt < 30; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 500))
    const pollResponse = await request.get(`/api/orders/${orderId}`, { headers: authHeaders(token) })
    const latest = parseBig.parse(await pollResponse.text()) as { status: number; verifyCode?: string }
    status = Number(latest.status)
    verifyCode = latest.verifyCode || ''
    if (status !== 1) break
  }
  expect(status, `订单 ${orderId} 支付后未变为"已付款"（status=${status}）`).toBe(2)
  expect(verifyCode, '支付成功后应生成核销码').toBeTruthy()

  return {
    orderId,
    verifyCode,
    totalFee: Number(order.totalFee),
    shopId: String(order.shopId),
    itemName: order.details?.[0]?.name || params.item.name,
  }
}

/**
 * 清空当前登录用户的购物车。
 *
 * 【为什么用例开头要清】合计断言的是"被选中商品"的金额；如果沿用上一次运行留下的条目，
 * 即便默认全选也只多不少，金额断言会变成对历史数据的依赖。清空后用 UI 重新加购，
 * 用例才是自洽的、可重复的。
 */
export async function clearCart(request: APIRequestContext, token: string): Promise<void> {
  const response = await request.get('/api/carts', { headers: authHeaders(token) })
  const text = await response.text()
  expect(response.ok(), `查询购物车失败 HTTP ${response.status()}：${text}`).toBeTruthy()
  const rows = parseBig.parse(text) as Array<{ id: string }>
  for (const row of rows) {
    const removed = await request.delete(`/api/carts/${row.id}`, { headers: authHeaders(token) })
    expect(removed.ok(), `删除购物车条目 ${row.id} 失败 HTTP ${removed.status()}`).toBeTruthy()
  }
}

/** 查询门店详情（收藏用例取门店名称） */
export async function getShop(
  request: APIRequestContext,
  shopId: string,
): Promise<{ id: string; name: string }> {
  const response = await request.get(`/api/shops/${shopId}`)
  const text = await response.text()
  expect(response.ok(), `查询门店 ${shopId} 失败 HTTP ${response.status()}：${text}`).toBeTruthy()
  return parseBig.parse(text) as { id: string; name: string }
}

/** 查询门店券列表（含当前用户是否已领取） */
export async function listShopVouchers(
  request: APIRequestContext,
  shopId: string,
): Promise<VoucherSummary[]> {
  const response = await request.get(`/api/vouchers/list?shopId=${shopId}`)
  const text = await response.text()
  expect(response.ok(), `查询门店券失败 HTTP ${response.status()}：${text}`).toBeTruthy()
  return parseBig.parse(text) as VoucherSummary[]
}
