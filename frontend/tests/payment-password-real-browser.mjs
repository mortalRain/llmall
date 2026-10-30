import { chromium, expect } from '@playwright/test'
import assert from 'node:assert/strict'

// Opt-in only: synthetic credentials are passed in the child environment, never recorded.
const required = key => { assert.ok(process.env[key], `Missing ${key}`); return process.env[key] }
const base = required('LLMALL_SMOKE_UI_BASE')
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext({ reducedMotion: 'reduce', viewport: { width: 1280, height: 900 } })
  const page = await context.newPage()
  await page.goto(base + '/login?redirect=/profile')
  await page.getByPlaceholder('请输入用户名').fill('smoke')
  await page.getByPlaceholder('请输入登录密码').fill(required('LLMALL_SMOKE_LOGIN'))
  await page.getByRole('button', { name: '进入 LLMall', exact: true }).click()
  await expect(page).toHaveURL(/\/profile/, { timeout: 15000 })
  await page.getByRole('button', { name: '修改支付口令', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByPlaceholder('原支付口令').fill('123456')
  await dialog.getByPlaceholder('新支付口令（6–32位）').fill(required('LLMALL_SMOKE_PAY'))
  await dialog.getByPlaceholder('再次输入新支付口令').fill(required('LLMALL_SMOKE_PAY'))
  await dialog.getByPlaceholder('账号绑定手机号').fill('13800138000')
  await dialog.getByPlaceholder('6位短信验证码').fill(required('LLMALL_SMOKE_CODE'))
  await dialog.getByRole('button', { name: '确认修改', exact: true }).click()
  await expect(page).toHaveURL(/\/login/, { timeout: 15000 })
  const storage = await page.evaluate(() => ({ ...localStorage }))
  assert.ok(!storage['llmall.accessToken'] && !storage['llmall.refreshToken'] && !storage['llmall.user'])
  console.log('Real browser login -> Profile -> payment password change -> session cleared and login redirect passed.')
} finally { await browser.close() }
