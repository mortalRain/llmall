import { expect, test } from '@playwright/test'
import { clearCart } from './support/api'
import { loginWithUi } from './support/ui'

/**
 * 商城闭环 1 · 加购 → 购物车改数量 → 合计正确
 *
 * 覆盖：
 * 1) /mall 商品卡片可加购（POST /carts 200）；
 * 2) /cart 能查到刚加购的条目（按 itemId 精确定位，不依赖历史数据）；
 * 3) 默认全选时合计 = 单价 × 1；
 * 4) 点击数量 +1 后 PUT /carts 200，合计与行小计同步变成 单价 × 2。
 *
 * 可重复跑：开头先通过接口清空购物车，再用 UI 重新加购一件。
 */
test('商城加购后购物车改数量，合计按单价×数量更新', async ({ page, request }) => {
  const token = await loginWithUi(page)
  await clearCart(request, token)

  await page.goto('/mall')
  const card = page.getByTestId('mall-card').first()
  await expect(card, '商城列表应至少有一张商品卡片').toBeVisible()

  const itemId = await card.getAttribute('data-item-id')
  const price = Number(await card.getAttribute('data-price'))
  expect(itemId, '商品卡片应带 data-item-id').toBeTruthy()
  expect(price, '商品卡片应带正数 data-price（分）').toBeGreaterThan(0)

  const addButton = card.getByRole('button', { name: '加入购物车' })
  await expect(addButton, '首张商品卡片应可加购').toBeEnabled()

  const [addResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes('/api/carts') && response.request().method() === 'POST',
    ),
    addButton.click(),
  ])
  expect(addResponse.status(), '加入购物车应返回 200').toBe(200)

  await page.goto('/cart')
  const row = page.locator(`[data-testid="cart-row"][data-item-id="${itemId}"]`)
  await expect(row, '刚加购的商品应出现在购物车').toBeVisible()

  const total = page.getByTestId('cart-total')
  await expect(total, '默认全选，合计应为单价 × 1').toHaveAttribute(
    'data-total-fen',
    String(price),
  )

  // 全选/取消全选：取消后合计归零，再选回后恢复；避免只有"默认全选"这一条路径被覆盖
  const selectAll = page.locator('.cart-head .el-checkbox')
  await selectAll.click()
  await expect(total, '取消全选后合计应为 0').toHaveAttribute('data-total-fen', '0')
  await selectAll.click()
  await expect(total, '重新全选后合计应恢复').toHaveAttribute('data-total-fen', String(price))

  const qtyInput = row.locator('.el-input-number input')
  await expect(qtyInput).toHaveValue('1')

  const [updateResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes('/api/carts') && response.request().method() === 'PUT',
    ),
    row.locator('.el-input-number__increase').click(),
  ])
  expect(updateResponse.status(), '修改购物车数量应返回 200').toBe(200)

  await expect(qtyInput, '数量应变成 2').toHaveValue('2')
  await expect(total, '合计应随数量同步为单价 × 2').toHaveAttribute(
    'data-total-fen',
    String(price * 2),
  )
  // 行小计同样按"单价 × 数量"展示，避免只有合计变、行金额没变
  const doubledYuan = (price * 2) / 100
  const expectedSubtotal = `¥${Number.isInteger(doubledYuan) ? doubledYuan : doubledYuan.toFixed(2)}`
  await expect(row.locator('.cart-subtotal'), '行小计应展示两件商品的金额').toHaveText(
    expectedSubtotal,
  )
})
