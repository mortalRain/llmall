import { expect, test } from '@playwright/test'
import {
  DEMO,
  authHeaders,
  createAddress,
  createPaidOrder,
  pickCheapestPackage,
  readJson,
} from './support/api'
import { loginWithUi, uniqueSuffix } from './support/ui'

/**
 * 冒烟 2 · 到店核销（顾客出示 / 员工核销 / 越权拦截）
 *
 * 语义：核销是**门店员工**的动作。顾客侧只应看到核销码并"到店出示给店员"，
 * 不应该出现"去核销"按钮；核销动作收敿在员工侧 `/staff/verify` 核销台。
 *
 * 覆盖：
 * 1) 顾客视角：订单卡片与详情里核销码可见、文案为"到店出示给店员"、没有核销按钮；
 * 2) 员工视角：Jack 是 10 号店 STAFF → 导航出现"核销台" → 选门店 #10 + 输入核销码 → 核销成功，
 *    订单列表状态变为"已核销"；
 * 3) 非员工访问核销台：拦截 /shops/my/staff 返回空（模拟非员工）→ 页面显示"仅门店员工可使用核销台"；
 * 4) 后端强制：非本店员工（11 号店订单）直接调核销接口 → 403 "仅门店员工可核销"，订单状态不变。
 *
 * 可重复跑：每轮新建并支付两笔订单（各店最便宜套餐），核销的是本轮新订单。
 */
test('核销：顾客出示核销码、员工在核销台核销、非员工被 403 拒绝', async ({ page, request }) => {
  const token = await loginWithUi(page)
  const suffix = uniqueSuffix()
  const addressId = await createAddress(request, token, `E2E${suffix}`, `E2E 核销路 ${suffix} 号`)

  const staffOrder = await createPaidOrder(request, token, {
    item: await pickCheapestPackage(request, DEMO.staffShopId),
    addressId,
  })
  const foreignOrder = await createPaidOrder(request, token, {
    item: await pickCheapestPackage(request, DEMO.nonStaffShopId),
    addressId,
  })

  // ---------- 1) 顾客视角：核销码可见、到店出示，没有核销按钮 ----------
  await page.goto('/orders')
  const staffCard = page.locator('.order-card').filter({ hasText: staffOrder.orderId })
  await expect(staffCard, '订单列表应包含刚支付的 10 号店订单').toBeVisible()
  await expect(staffCard.getByText('待使用', { exact: true })).toBeVisible()

  const verifyStrip = staffCard.locator('.verify-strip')
  await expect(verifyStrip, '顾客侧应突出展示核销码').toContainText(staffOrder.verifyCode)
  await expect(verifyStrip).toContainText('到店出示给店员')
  await expect(
    staffCard.getByRole('button', { name: '去核销' }),
    '顾客的订单操作里不应出现核销按钮',
  ).toHaveCount(0)

  await staffCard.getByRole('button', { name: '查看详情' }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '订单详情' })
  await expect(drawer).toBeVisible()
  await expect(drawer.locator('.big-verify')).toContainText(staffOrder.verifyCode)
  await expect(drawer.locator('.big-verify')).toContainText('到店出示给店员')
  await expect(drawer.getByRole('button', { name: '去核销' })).toHaveCount(0)
  await drawer.locator('.el-drawer__close-btn').click()
  await expect(drawer).toBeHidden()

  // ---------- 2) 员工视角：进入核销台，选门店 #10 执行核销 ----------
  const staffNav = page.locator('.side-nav').getByRole('link', { name: '核销台' })
  await expect(staffNav, '员工可见"核销台"入口').toBeVisible()
  await staffNav.click()
  await expect(page).toHaveURL(/\/staff\/verify/)
  await expect(page.getByText('员工模式')).toBeVisible()

  await page.getByRole('button', { name: '门店 #10' }).click()
  await page.getByPlaceholder('请输入顾客出示的核销码').fill(staffOrder.verifyCode)

  const [verifyResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes('/api/orders/verify') && response.request().method() === 'POST',
    ),
    page.getByRole('button', { name: '确认核销' }).click(),
  ])
  expect(verifyResponse.status(), '员工核销应返回 200').toBe(200)
  await expect(page.locator('.el-message').filter({ hasText: '核销成功' })).toBeVisible()
  await expect(page.getByText('核销成功', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(`订单 ${staffOrder.orderId} 已核销`)).toBeVisible()

  // 订单状态 2 → 7
  await page.goto('/orders')
  await expect(staffCard.getByText('已核销', { exact: true })).toBeVisible()

  // ---------- 3) 非员工访问核销台：拦截员工列表为空，页面应拒绝 ----------
  await page.route('**/api/shops/my/staff', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  )
  await page.goto('/staff/verify')
  await expect(page.getByText('仅门店员工可使用核销台')).toBeVisible()
  await expect(page.getByPlaceholder('请输入顾客出示的核销码')).toHaveCount(0)
  await page.unroute('**/api/shops/my/staff')

  // ---------- 4) 后端强制：绕过 UI 直接调核销接口，非本店员工必须 403 ----------
  const forbidden = await request.post('/api/orders/verify', {
    headers: authHeaders(token),
    data: { verifyCode: foreignOrder.verifyCode, shopId: DEMO.nonStaffShopId },
  })
  expect(forbidden.status(), '非员工核销必须被后端 403 拒绝').toBe(403)
  const body = await readJson<{ code: number; msg: string }>(forbidden)
  expect(body.msg).toContain('仅门店员工可核销')

  // 被拒绝的订单不能被改动：重新查详情仍应为"待使用"
  const foreignDetail = await request.get(`/api/orders/${foreignOrder.orderId}`, {
    headers: authHeaders(token),
  })
  const foreignBody = await readJson<{ status: number }>(foreignDetail)
  expect(Number(foreignBody.status), '403 拒绝后订单状态不得变化').toBe(2)
})
