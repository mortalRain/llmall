import { expect, test } from '@playwright/test'
import {
  DEMO,
  apiLogin,
  authHeaders,
  createAddress,
  createPaidOrder,
  pickCheapestPackage,
  readJson,
} from './support/api'
import { loginWithUi, uniqueSuffix } from './support/ui'

/**
 * 冒烟 4 · 真实非员工账号 Rose：核销台拒绝 + 直接调接口 403
 *
 * 与冒烟 2 的"非员工被拦截"互补：冒烟 2 用 Jack 去核销 11 号店（店不是他的），
 * 验证的是"跨店越权"；本用例用**完全不在 shop_staff 里的真实账号 Rose**，
 * 验证的是"根本不是员工"这条更基础的分支 —— 两个用例一起覆盖 403 的两种来源。
 *
 * 断言：
 * 1) Rose/123456 能通过登录页正常登录（账号是真的，不是 mock）；
 * 2) 访问 /staff/verify 显示"仅门店员工可使用核销台"，且不渲染核销表单；
 * 3) 绕过 UI，用 Rose 的 token 直接 POST /orders/verify（shop 10 的已支付订单码 + shopId=10）
 *    → 403 "仅门店员工可核销"；
 * 4) 被拒绝后订单状态仍为 2（已付款待使用），verifyTime 为空 —— 拒绝不能产生副作用。
 *
 * 可重复跑：每轮由 Jack 新建并支付一笔 10 号店订单，Rose 尝试核销它。
 */
test('非员工 Rose：核销台拒绝 + 直接调核销接口 403 且订单状态不变', async ({ page, request }) => {
  const suffix = uniqueSuffix()

  // Jack 负责造数：新增地址 + 10 号店已支付订单（核销码是顾客侧持有的凭证）
  const jackSession = await apiLogin(request)
  const jackToken = jackSession.accessToken
  const addressId = await createAddress(request, jackToken, `E2E${suffix}`, `E2E 越权路 ${suffix} 号`)
  const item = await pickCheapestPackage(request, DEMO.staffShopId)
  const order = await createPaidOrder(request, jackToken, { item, addressId })

  // ---------- 1) 真实账号 Rose 登录（不是接口伪造 token） ----------
  const roseToken = await loginWithUi(page, DEMO.nonStaffUsername, DEMO.nonStaffPassword)
  expect(roseToken, 'Rose 登录后应拿到 accessToken').toBeTruthy()

  // ---------- 2) 非员工访问核销台：页面必须拒绝 ----------
  await page.goto('/staff/verify')
  await expect(page.getByText('仅门店员工可使用核销台')).toBeVisible()
  await expect(page.getByPlaceholder('请输入顾客出示的核销码')).toHaveCount(0)
  await expect(page.getByText('员工模式')).toHaveCount(0)

  // 顺带确认：Rose 的任职门店列表为空（服务端事实，而不是前端隐藏）
  const staffShops = await request.get('/api/shops/my/staff', { headers: authHeaders(roseToken) })
  expect(staffShops.status(), '已登录的非员工应能查询任职列表').toBe(200)
  const staffShopBody = await readJson<unknown[]>(staffShops)
  expect(staffShopBody, 'Rose 不应是任何门店的员工').toEqual([])

  // ---------- 3) 绕过 UI 直接调核销接口：必须 403 ----------
  const forbidden = await request.post('/api/orders/verify', {
    headers: authHeaders(roseToken),
    data: { verifyCode: order.verifyCode, shopId: DEMO.staffShopId },
  })
  expect(forbidden.status(), '非员工即使拿到 10 号店的核销码也必须被 403 拒绝').toBe(403)
  const body = await readJson<{ code: number; msg: string }>(forbidden)
  expect(body.msg).toContain('仅门店员工可核销')

  // ---------- 4) 拒绝不能有副作用：订单仍是"已付款待使用" ----------
  const detail = await request.get(`/api/orders/${order.orderId}`, { headers: authHeaders(jackToken) })
  expect(detail.ok(), '失败订单详情查询应成功').toBeTruthy()
  const latest = await readJson<{ status: number; verifyTime: string | null }>(detail)
  expect(Number(latest.status), '403 拒绝后订单状态不得变化').toBe(2)
  expect(latest.verifyTime, '403 拒绝后不得写入核销时间').toBeNull()
})
