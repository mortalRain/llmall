import { chromium, expect } from '@playwright/test'
import assert from 'node:assert/strict'

const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext()
  const page = await context.newPage()
  const user = { userId: 'fixture-user', username: 'fixture', nickName: 'Fixture', balance: 10000, credits: 0 }
  await context.addInitScript(user => {
    if (location.pathname !== '/login') {
      localStorage.setItem('llmall.user', JSON.stringify(user))
      localStorage.setItem('llmall.accessToken', 'fixture-token')
      localStorage.setItem('llmall.refreshToken', 'fixture-refresh')
    }
  }, user)
  let codes = 0, changes = 0, rejectChange = true, releaseChange
  await context.route(url => url.pathname.startsWith('/api/'), async route => {
    const req = route.request(), url = new URL(req.url())
    let data = null
    if (url.pathname === '/api/users/me') data = user
    else if (url.pathname === '/api/users/code') {
      codes++
      assert.equal(req.method(), 'POST')
      assert.equal(url.searchParams.get('phone'), '13800138000')
      assert.equal(url.searchParams.get('scene'), 'change-pay-password')
    } else if (url.pathname === '/api/users/pay-password') {
      changes++
      assert.deepEqual(req.postDataJSON(), { oldPassword: '123456', newPassword: 'secure654321', code: '246810' })
      if (rejectChange) {
        await route.fulfill({ status: 400, json: { code: 400, msg: '验证码已失效', data: null } })
        return
      }
      await new Promise(resolve => { releaseChange = resolve })
    } else if (url.pathname === '/api/vouchers/my') data = []
    else if (url.pathname === '/api/shops/my/staff') data = []
    else throw new Error('Unexpected API: ' + req.method() + ' ' + url.pathname)
    await route.fulfill({ json: { code: 200, msg: 'OK', data } })
  })
  await page.goto('http://127.0.0.1:5192/profile')
  await page.getByRole('button', { name: '修改支付口令', exact: true }).click({ timeout: 5000 })
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  if (process.env.LLMALL_UI_SCREENSHOTS) {
    for (const [width, height] of [[1280, 900], [375, 812]]) {
      await page.setViewportSize({ width, height })
      await page.screenshot({ path: `${process.env.LLMALL_UI_SCREENSHOTS}/pay-password-${width}.png`, fullPage: true, animations: 'disabled' })
    }
    await page.setViewportSize({ width: 1280, height: 900 })
  }
  await dialog.getByRole('button', { name: '确认修改', exact: true }).click()
  assert.equal(changes, 0)
  await expect(dialog.getByRole('alert')).toContainText('原支付口令')
  await dialog.getByPlaceholder('账号绑定手机号').fill('13800138000')
  await dialog.getByRole('button', { name: '发送验证码', exact: true }).click()
  await expect.poll(() => codes).toBe(1)
  await expect(dialog.getByRole('button', { name: /秒后重发/ })).toBeDisabled()
  await dialog.getByPlaceholder('原支付口令').fill('123456')
  await dialog.getByPlaceholder('新支付口令（6–32位）').fill('secure654321')
  await dialog.getByPlaceholder('再次输入新支付口令').fill('different')
  await dialog.getByPlaceholder('6位短信验证码').fill('246810')
  await dialog.getByRole('button', { name: '确认修改', exact: true }).click()
  assert.equal(changes, 0)
  await expect(dialog.getByRole('alert')).toContainText('不一致')
  await dialog.getByPlaceholder('再次输入新支付口令').fill('secure654321')
  await dialog.getByRole('button', { name: '确认修改', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('验证码已失效')
  assert.equal(changes, 1)
  assert.ok(await page.evaluate(() => Boolean(localStorage.getItem('llmall.accessToken'))))
  await dialog.getByPlaceholder('6位短信验证码').fill('246810')
  rejectChange = false
  await dialog.getByRole('button', { name: '确认修改', exact: true }).click()
  await expect.poll(() => changes).toBe(2)
  await expect(dialog.getByRole('button', { name: '确认修改', exact: true })).toBeDisabled()
  assert.ok(!await page.evaluate(() => JSON.stringify({ ...localStorage }).includes('secure654321')))
  releaseChange()
  await expect(page).toHaveURL(/\/login/)
  const storage = await page.evaluate(() => ({ ...localStorage }))
  assert.ok(!storage['llmall.accessToken'] && !storage['llmall.refreshToken'] && !storage['llmall.user'])
  console.log('Password UI: validation, scene, cooldown, backend rejection, single-submit, no secret persistence, success relogin passed.')
} finally { await browser.close() }
