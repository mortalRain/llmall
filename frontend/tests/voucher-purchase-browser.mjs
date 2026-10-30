// Offline UI regression: start Vite on 127.0.0.1:5189 before running this script.
import { chromium, expect } from '@playwright/test'
import assert from 'node:assert/strict'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext()
  const page = await context.newPage()
  const user = { userId: 'alice', username: 'alice', nickName: 'Alice', balance: 10000, credits: 0 }
  await page.addInitScript(user => {
    if (!localStorage.getItem('llmall.user')) {
      localStorage.setItem('llmall.user', JSON.stringify(user))
      localStorage.setItem('llmall.accessToken', 'test-token')
    }
  }, user)
  let state = 'ACTIVE'
  let posts = 0
  let queries = 0
  let requestId = ''
  let holdReceive
  let onReceive = () => {}
  const voucher = { id: 'v1', shopId: 's1', title: '普通付费券', subTitle: '', rules: null, payValue: 100, actualValue: 200, type: 0, status: 1, beginTime: null, endTime: null, remainingStock: null, inSeckillTime: null, received: false }
  await page.context().route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    let body = []
    if (path === '/api/users/me') body = user
    else if (path === '/api/shops/s1') body = { id: 's1', name: '测试门店', images: null, score: 50, status: 1, address: '测试地址', avgPrice: 100 }
    else if (path === '/api/vouchers/list') body = [voucher, { ...voucher, id: 'v2', title: '秒杀券', type: 1, remainingStock: 0 }]
    else if (path === '/api/blogs/shop/s1') body = { list: [], total: 0, pages: 0 }
    else if (path.endsWith('/favorite')) body = false
    else if (path === '/api/vouchers/v1/receive') {
      posts++
      const data = route.request().postDataJSON()
      requestId = data.requestId
      assert.equal(data.pw, 'my-pay-secret')
      assert.match(requestId, /^[A-Za-z0-9_-]{16,64}$/)
      const stored = await page.evaluate(() => ({ ...localStorage }))
      assert.equal(stored['llmall:voucher-purchase:v1:alice:v1'], requestId)
      assert.ok(!JSON.stringify(stored).includes('my-pay-secret'))
      onReceive()
      if (holdReceive) await holdReceive
      await route.abort()
      return
    } else if (path.startsWith('/api/vouchers/purchases/')) {
      queries++
      body = { requestId, state, voucherId: 'v1', amount: 100, userVoucherId: state === 'ISSUED' ? 'uv1' : null }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('http://127.0.0.1:5189/shops/s1')
  const card = page.locator('.voucher-card').filter({ hasText: '普通付费券' })
  await card.getByRole('button').click()
  await page.locator('.el-message-box input').fill('my-pay-secret')
  await page.getByRole('button', { name: '确认购买', exact: true }).click()
  await expect(card.getByRole('status')).toContainText('购买处理中', { timeout: 15000 })
  assert.equal(posts, 1)
  assert.equal(queries, 5)
  state = 'ISSUED'
  await page.reload()
  await expect(card.getByRole('button', { name: '已领取' })).toBeVisible()
  assert.equal(posts, 1)
  assert.equal(await page.evaluate(() => localStorage.getItem('llmall:voucher-purchase:v1:alice:v1')), null)
  await page.locator('.voucher-card').filter({ hasText: '秒杀券' }).getByRole('button').click()
  await expect(page.getByText('该券为秒杀券，请通过秒杀活动入口购买，普通领取不适用')).toBeVisible()
  assert.equal(posts, 1)
  console.log('PASS browser: persisted-before-POST, password absent, polling=5, refresh ISSUED cleanup, seckill blocked; POST count=1')

  state = 'ACTIVE'
  await page.reload()
  const secondPage = await page.context().newPage()
  await secondPage.goto('http://127.0.0.1:5189/shops/s1')
  const secondCard = secondPage.locator('.voucher-card').filter({ hasText: '普通付费券' })
  let releaseReceive
  holdReceive = new Promise(resolve => { releaseReceive = resolve })
  const sent = new Promise(resolve => { onReceive = resolve })
  await card.getByRole('button').click()
  await page.locator('.el-message-box input').fill('my-pay-secret')
  await page.getByRole('button', { name: '确认购买', exact: true }).click()
  await sent
  const firstTabKey = requestId
  await secondCard.getByRole('button').click()
  await expect.poll(async () => secondPage.evaluate(async () => (await navigator.locks.query()).pending.length)).toBeGreaterThan(0)
  await expect(secondPage.locator('.el-message-box')).not.toBeVisible()
  assert.equal(await secondPage.evaluate(() => localStorage.getItem('llmall:voucher-purchase:v1:alice:v1')), firstTabKey)
  releaseReceive()
  await expect(card.getByRole('status')).toContainText('购买处理中', { timeout: 15000 })
  await expect(secondCard.getByRole('status')).toContainText('购买处理中', { timeout: 25000 })
  await expect(secondPage.locator('.el-message-box')).not.toBeVisible()
  assert.equal(posts, 2)
  assert.equal(await secondPage.evaluate(() => localStorage.getItem('llmall:voucher-purchase:v1:alice:v1')), firstTabKey)
  await secondPage.evaluate(() => Object.defineProperty(navigator, 'locks', { value: undefined }))
  await secondCard.getByRole('button').click()
  await expect(secondPage.getByText(/浏览器不支持安全的跨标签页锁/)).toBeVisible()
  await expect(secondPage.locator('.el-message-box')).not.toBeVisible()
  assert.equal(posts, 2)
  console.log('PASS browser: real shared-context tabs serialize confirmation/POST and preserve first pending key; absent Web Locks blocks payment')
} finally {
  await browser.close()
}
