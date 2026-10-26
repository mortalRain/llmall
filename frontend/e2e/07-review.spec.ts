import { expect, test } from '@playwright/test'
import { readJson } from './support/api'
import { loginWithUi } from './support/ui'

/**
 * 门店互动 3 · 评价列表非空并展示评分/内容
 *
 * 前置：sql/merged/35-review-seed.sql 已执行（10 号店 6 条评价）。
 * 覆盖：
 * 1) 门店详情有"门店评价"区，条数 > 0；
 * 2) 每条评价展示 4~5 的评分与真实中文内容（不是空壳占位）；
 * 3) /blogs/shop/10 分页结构 total/list 可用（与 UI 同源）。
 */
test('门店详情评价列表非空并展示评分与内容', async ({ page, request }) => {
  await loginWithUi(page)

  // 接口侧先确认种子数据与分页结构：total 是字符串、list 是数组（Long 已序列化为字符串）
  const response = await request.get('/api/blogs/shop/10?page=1&size=10')
  expect(response.ok(), '查询门店评价接口应返回 200').toBeTruthy()
  const body = await readJson<{ total: number | string; pages: number | string; list: unknown[] }>(
    response,
  )
  expect(Number(body.total), '10 号店评价总数应 >= 6').toBeGreaterThanOrEqual(6)
  expect(Array.isArray(body.list), '评价接口应返回分页 list').toBeTruthy()

  await page.goto('/shops/10')
  const section = page.getByTestId('review-section')
  await expect(section, '门店详情应展示评价区').toBeVisible()
  await expect(section.getByText('门店评价', { exact: true })).toBeVisible()

  const reviews = section.getByTestId('review-item')
  await expect(reviews.first(), '评价列表应至少有一条').toBeVisible()
  expect(await reviews.count(), '首屏评价条数应 > 0').toBeGreaterThan(0)

  const first = reviews.first()
  const scoreText = (await first.locator('.review-score').innerText()).trim()
  expect(scoreText, '评价应展示 4~5 的评分').toMatch(/^[45]\.0$/)
  const starsOn = await first.locator('.review-stars .on').count()
  expect(starsOn, '星级应与评分一致（4~5 颗）').toBeGreaterThanOrEqual(4)

  const content = (await first.locator('.review-content').innerText()).trim()
  expect(content.length, '评价内容应为真实中文正文，而不是空串').toBeGreaterThan(10)
  expect(content, '评价内容应包含中文').toMatch(/[\u4e00-\u9fa5]/)

  const author = (await first.locator('.review-author b').innerText()).trim()
  expect(author.length, '评价作者昵称不应为空').toBeGreaterThan(0)
  expect(author, '评价作者应为真实昵称或「同城用户{id}」回退').toMatch(/同城用户|[\u4e00-\u9fa5]/)
})
