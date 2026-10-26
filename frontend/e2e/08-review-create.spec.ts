import { expect, test } from '@playwright/test'
import { authHeaders, readJson } from './support/api'
import { loginWithUi, uniqueSuffix } from './support/ui'

/**
 * 门店互动 4 · 写评价闭环
 *
 * 覆盖：
 * 1) 登录后门店详情出现「写评价」入口，进入独立写评价页；
 * 2) el-rate 评分 + 标题 + 内容（10~500 字）提交 POST /blogs 200；
 * 3) 回到门店详情后评价区刷新，新评价出现在列表中；
 * 4) 评价项展示头像、昵称、评分与内容（昵称为空时后端/前端回退「同城用户{id}」）。
 *
 * 可重复跑：标题/内容带唯一后缀，断言按内容定位，不依赖列表顺序与历史数据。
 * 截图：docs/images/review-author.png（评价列表：头像+昵称+评分+内容）、
 *       docs/images/review-form.png（写评价页）。
 */
test('写评价后门店详情出现昵称/评分/内容', async ({ page, request }) => {
  const token = await loginWithUi(page)
  // 接口侧先确认当前登录用户（昵称字段为空时用于断言回退文案），同时验证令牌可用
  const meResponse = await request.get('/api/users/me', { headers: authHeaders(token) })
  expect(meResponse.ok(), '查询当前用户应返回 200').toBeTruthy()
  const me = await readJson<{ userId: string; nickName?: string | null }>(meResponse)
  // /users/me 的昵称会把空昵称回退成用户名；内部资料接口返回原始空值，
  // 所以评价作者可能是真实昵称，也可能是「同城用户{id}」回退文案，两种都接受。
  const profileNickname = (me.nickName || '').trim()
  const fallbackAuthor = `同城用户${me.userId}`

  await page.goto('/shops/10')
  const section = page.getByTestId('review-section')
  await expect(section, '门店详情应展示评价区').toBeVisible()
  const writeButton = page.getByTestId('write-review')
  await expect(writeButton, '登录后应出现「写评价」按钮').toBeVisible()
  await writeButton.click()
  await expect(page).toHaveURL(/\/shops\/10\/review/, { timeout: 15_000 })

  const form = page.getByTestId('review-form')
  await expect(form, '写评价页应展示表单').toBeVisible()

  const suffix = uniqueSuffix()
  const title = `E2E 评价 ${suffix}`
  const content = `这是自动化冒烟提交的评价内容 ${suffix}，环境干净、服务响应及时，整体体验不错。`

  // el-rate 的第 5 颗星：评分必须真实进入表单（提交后断言 5.0）
  await page.locator('[data-testid="review-score"] .el-rate__item').nth(4).click()
  // Element Plus 的 ElInput 默认把透传属性挂到内部 input/textarea 上，data-testid 就是输入框本身
  await page.getByTestId('review-title').fill(title)
  await page.getByTestId('review-content').fill(content)
  await page.getByTestId('review-image').fill('https://example.com/e2e-review.jpg')

  await page.screenshot({ path: '../docs/images/review-form.png', fullPage: true })

  const [publishResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes('/api/blogs') &&
        response.request().method() === 'POST' &&
        !response.url().includes('/like') &&
        !response.url().includes('/comments'),
    ),
    page.getByTestId('review-submit').click(),
  ])
  expect(publishResponse.status(), '发布评价应返回 200').toBe(200)

  // 成功后回到门店详情，并定位到评价区
  await expect(page).toHaveURL(/\/shops\/10/, { timeout: 15_000 })
  await expect(section, '回到门店详情后应展示评价区').toBeVisible()

  // 新评价必须出现，且内容/标题/评分/作者都可见
  const newReview = section.getByTestId('review-item').filter({ hasText: content })
  await expect(newReview, '新评价应出现在门店评价列表').toHaveCount(1)

  const authorText = (await newReview.getByTestId('review-author').innerText()).trim()
  expect(authorText, '评价作者不能为空').toBeTruthy()
  expect(
    [profileNickname, fallbackAuthor],
    `评价作者应为真实昵称「${profileNickname}」或回退「${fallbackAuthor}」，实际为「${authorText}」`,
  ).toContain(authorText)
  expect((await newReview.locator('.review-score').innerText()).trim(), '评分应为 5.0').toBe('5.0')
  await expect(newReview.locator('.review-content')).toContainText(content)
  await expect(newReview.getByTestId('review-avatar'), '评价应展示头像').toBeVisible()

  // 评价卡片截图（含头像/昵称/评分/内容/时间）；等提示消失，避免 toast 遮挡证据图
  await page.waitForTimeout(3200)
  await newReview.scrollIntoViewIfNeeded()
  await newReview.screenshot({ path: '../docs/images/review-author.png' })
})
