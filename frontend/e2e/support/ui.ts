import { expect, type Page } from '@playwright/test'
import { DEMO } from './api'

/**
 * 通过登录页真实登录（不是直接塞 token）：所有冒烟都从"用户能登录"开始，
 * 登录态、Pinia 初始化、路由守卫这些链路才是在测真实路径。
 *
 * @param username 默认演示账号 Jack；Rose 用例传非员工账号
 * @returns 登录后 localStorage 里的 accessToken，供后续接口造数使用
 */
export async function loginWithUi(
  page: Page,
  username: string = DEMO.username,
  password: string = DEMO.password,
): Promise<string> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入登录密码').fill(password)
  await page.getByRole('button', { name: '进入 LLMall' }).click()

  // 登录成功会 replace 到 /feed（或 redirect 参数指定的路径）
  await expect(page).toHaveURL(/\/feed/, { timeout: 20_000 })
  const token = await page.evaluate(() => window.localStorage.getItem('llmall.accessToken') || '')
  expect(token, '登录后 localStorage 应写入 llmall.accessToken').toBeTruthy()
  return token
}

/** 每次运行生成唯一后缀：地址/联系人可重复创建，断言不会命中上一次的数据 */
export function uniqueSuffix(): string {
  return `${Date.now().toString().slice(-6)}${Math.floor(Math.random() * 1000)
    .toString()
    .padStart(3, '0')}`
}
