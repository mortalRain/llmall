import { expect, test } from '@playwright/test'
import { DEMO, listShopVouchers } from './support/api'
import { loginWithUi } from './support/ui'

/**
 * 冒烟 3 · 付费券购买
 *
 * 门店详情（10 号店）点付费券"购买" → 输入支付口令 888888 → 断言按钮变"已领取"，
 * 并到"我的"页面确认该券出现在我的优惠券里。
 *
 * 可重复跑：券是"一人一张"，上一轮已经买过时按钮就是"已领取"，
 * 此时跳过购买动作、只断言状态与"我的券"（不重复扣款）。
 */
test('付费券购买：口令 888888 后变为已领取并出现在我的券', async ({ page, request }) => {
  await loginWithUi(page)

  const vouchers = await listShopVouchers(request, DEMO.staffShopId)
  const paidVoucher = vouchers.find((voucher) => Number(voucher.payValue) > 0)
  expect(paidVoucher, '10 号店应有付费券（演示数据 voucher 8004）').toBeTruthy()

  await page.goto(`/shops/${DEMO.staffShopId}`)
  const card = page.locator('.voucher-card').filter({ hasText: paidVoucher!.title })
  await expect(card, '门店详情应展示这张付费券').toBeVisible()

  const receivedButton = card.getByRole('button', { name: '已领取' })
  if ((await receivedButton.count()) > 0) {
    // 上一轮已经购买过：验证"已领取"状态可复用，不重复扣款
    await expect(receivedButton).toBeVisible()
  } else {
    const buyButton = card.getByRole('button', { name: /购买/ })
    await expect(buyButton).toBeVisible()
    await buyButton.click()

    const messageBox = page.locator('.el-message-box')
    await expect(messageBox, '购买付费券应弹出支付口令输入框').toBeVisible()
    await messageBox.locator('input[type="password"]').fill(DEMO.payPassword)
    await messageBox.getByRole('button', { name: '确认购买' }).click()

    await expect(page.locator('.el-message').filter({ hasText: '领取成功' })).toBeVisible()
    await expect(card.getByRole('button', { name: '已领取' })).toBeVisible()
  }

  // "我的券"：券已经落到当前用户名下
  await page.goto('/profile')
  await expect(
    page.getByText(paidVoucher!.title).first(),
    '我的优惠券应包含这张付费券',
  ).toBeVisible()
})
