import { defineConfig } from '@playwright/test'

/**
 * Playwright 冒烟测试配置（9 条：新增地址 / 到店核销 / 付费券购买 / 非员工 Rose 越权 403 /
 * 商城加购+购物车改数量 / 门店收藏 / 门店评价 / 写评价 / 多门店券降级提示）。
 * 另：375×812 移动端冒烟独立为 scripts/mobile-smoke.mjs（npm run test:mobile），不占本配置的 9 条。
 *
 * 【运行】cd frontend && npm run test:e2e
 *   前置：后端网关已跑在 8080，Redis/MySQL/Nacos/MQ 已起（演示环境 docker compose）。
 *   dev server 未起时由 webServer 自动 `npm run dev`；已在跑则直接复用（reuseExistingServer）。
 *
 * 【为什么用本机 Chrome（channel: 'chrome'）】
 * 不额外下载 Playwright 自带浏览器（体积大、内网常下载失败），直接用开发机上已安装的 Chrome。
 *
 * 【为什么 workers: 1】
 * 多条用例都会操作用户 Jack 的订单/余额/券/购物车/收藏，并行跑会相互抢库存与余额；
 * 串行且独立建数据（唯一后缀、每轮新订单、用例开头清理购物车/收藏）才能稳定重复执行。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    channel: 'chrome',
    headless: true,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
})
