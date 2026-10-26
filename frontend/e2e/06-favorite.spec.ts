import { expect, test } from '@playwright/test'
import { authHeaders, getShop } from './support/api'
import { loginWithUi } from './support/ui'

/**
 * 门店互动 2 · 收藏 → 我的收藏出现 → 取消收藏
 *
 * 覆盖：
 * 1) 门店详情收藏按钮初始为"收藏门店"，点击后 POST /shops/10/favorite 200，按钮变"已收藏"；
 * 2) /favorites 列表出现 10 号店（GET /shops/my/favorites 只含本人）；
 * 3) 在收藏页点"取消收藏" DELETE 200，卡片从列表消失。
 *
 * 可重复跑：开头先 DELETE 一次清掉历史收藏（接口本身幂等），保证初始状态是未收藏。
 */
test('门店详情收藏后出现在我的收藏，取消后消失', async ({ page, request }) => {
  const token = await loginWithUi(page)
  // 幂等清理：历史运行可能留下收藏，先删成"未收藏"初始态
  const reset = await request.delete('/api/shops/10/favorite', { headers: authHeaders(token) })
  expect(reset.ok(), '清理历史收藏应返回 200').toBeTruthy()
  const shop = await getShop(request, '10')

  await page.goto('/shops/10')
  const toggle = page.getByTestId('favorite-toggle')
  await expect(toggle, '门店详情应展示收藏按钮').toBeVisible()
  await expect(toggle, '清理后初始态应为未收藏').toContainText('收藏门店')

  const [favoriteResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes('/api/shops/10/favorite') &&
        response.request().method() === 'POST',
    ),
    toggle.click(),
  ])
  expect(favoriteResponse.status(), '收藏接口应返回 200').toBe(200)
  await expect(toggle, '收藏成功后按钮应高亮为已收藏').toContainText('已收藏')

  await page.goto('/favorites')
  const favoriteCard = page.locator('[data-testid="favorite-card"][data-shop-id="10"]')
  await expect(favoriteCard, '我的收藏应出现 10 号店').toBeVisible()
  await expect(favoriteCard, '收藏卡片应展示门店名称').toContainText(shop.name)

  const [unfavoriteResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes('/api/shops/10/favorite') &&
        response.request().method() === 'DELETE',
    ),
    favoriteCard.getByRole('button', { name: '取消收藏' }).click(),
  ])
  expect(unfavoriteResponse.status(), '取消收藏接口应返回 200').toBe(200)
  await expect(favoriteCard, '取消收藏后卡片应从我的收藏消失').toHaveCount(0)
})
