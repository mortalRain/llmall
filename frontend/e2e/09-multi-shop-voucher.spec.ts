import { expect, test } from '@playwright/test'
import {
  authHeaders,
  clearCart,
  pickCheapestPackage,
  readJson,
} from './support/api'
import { loginWithUi } from './support/ui'

interface UserVoucherRow {
  id: string
  title: string
  shopId: string | null
  status: number
}

/**
 * 结算边界 · 多门店购物车只展示通用券
 *
 * 覆盖：
 * 1) 购物车同时含 1 号店与 10 号店的商品（跨 shopId）；
 * 2) 结算页出现「多门店结算仅支持通用券」提示；
 * 3) 我的券里属于这两个门店的券不会出现在可用券列表（只保留 shopId 为空的通用券）。
 *
 * 可重复跑：开头清空购物车，用两个不同门店的最便宜套餐通过接口加购；
 * 券列表按用户实际持有的券查询，不依赖某个券一定存在。
 */
test('多门店购物车结算只展示通用券并提示', async ({ page, request }) => {
  const token = await loginWithUi(page)
  await clearCart(request, token)

  const [shopOneItem, shopTenItem] = await Promise.all([
    pickCheapestPackage(request, '1'),
    pickCheapestPackage(request, '10'),
  ])

  for (const item of [shopOneItem, shopTenItem]) {
    const response = await request.post('/api/carts', {
      headers: authHeaders(token),
      data: {
        itemId: item.id,
        name: item.name,
        spec: '到店核销 · E2E 多门店',
        price: Number(item.price),
      },
    })
    const text = await response.text()
    expect(response.ok(), `加入购物车失败 HTTP ${response.status()}：${text}`).toBeTruthy()
  }

  await page.goto('/cart')
  await expect(
    page.locator('[data-testid="cart-row"]'),
    '购物车应包含两个门店的商品',
  ).toHaveCount(2)

  await page.getByRole('button', { name: /去结算/ }).click()
  await expect(page).toHaveURL(/\/checkout/, { timeout: 15_000 })

  const hint = page.getByTestId('multi-shop-voucher-hint')
  await expect(hint, '多门店结算应展示降级提示').toBeVisible()
  await expect(hint).toContainText('多门店结算仅支持通用券')

  // 门店券不得出现在可用券列表：先查当前用户持有的未使用券，再逐张断言
  const voucherResponse = await request.get('/api/vouchers/my?status=1', {
    headers: authHeaders(token),
  })
  expect(voucherResponse.ok(), '查询我的券应返回 200').toBeTruthy()
  const myVouchers = await readJson<UserVoucherRow[]>(voucherResponse)
  const shopVouchers = (Array.isArray(myVouchers) ? myVouchers : []).filter(
    (voucher) => voucher.shopId && ['1', '10'].includes(String(voucher.shopId)),
  )
  for (const voucher of shopVouchers) {
    await expect(
      page.locator('.voucher-option').filter({ hasText: voucher.title }),
      `门店券「${voucher.title}」不应出现在多门店结算的可用券中`,
    ).toHaveCount(0)
  }

  await page.screenshot({ path: '../docs/images/checkout-multi-shop.png', fullPage: true })

  // 还原现场，避免后续手工/移动端冒烟受这两个购物车行影响
  await clearCart(request, token)
})
