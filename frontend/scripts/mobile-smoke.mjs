/**
 * 375×812 移动端冒烟（Playwright，独立于 e2e 测试集）。
 *
 * 运行：
 *   1) 后端网关 8080 + 前端 dev server 5173（或设置 LLMALL_WEB_URL）
 *   2) cd frontend && npm run test:mobile
 *
 * 覆盖：登录 → 商城 → 加购 → 购物车 → 门店详情 → 门店评价 → 写评价页。
 * 断言：
 *   · 每个页面 html.scrollWidth 不超过视口（无横向溢出）；
 *   · 关键按钮滚动到可视区后，中心点没有被底部固定导航/顶栏盖住（可点击）；
 *   · 输出 docs/images/mobile-mall.png、mobile-shop.png（另附 mobile-review-form.png）。
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const baseURL = process.env.LLMALL_WEB_URL || 'http://localhost:5173'
const outDir = resolve(process.cwd(), '../docs/images')
mkdirSync(outDir, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({
  baseURL,
  viewport: { width: 375, height: 812 },
  deviceScaleFactor: 2,
  isMobile: true,
  hasTouch: true,
  locale: 'zh-CN',
  timezoneId: 'Asia/Shanghai',
})
const page = await context.newPage()

const failures = []

function assert(condition, message) {
  if (!condition) {
    failures.push(message)
    console.error(`  ✗ ${message}`)
  } else {
    console.log(`  ✓ ${message}`)
  }
}

/** 横向溢出：scrollWidth 超过布局视口，或存在明显越界的可见元素 */
async function assertNoHorizontalOverflow(label) {
  const result = await page.evaluate(() => {
    // 注意用 clientWidth（布局视口）而不是 innerWidth：出现横向溢出时 innerWidth 会被撑大，
    // 拿它做基准会把 10px 的溢出误判成"刚好占满"。
    const vw = document.documentElement.clientWidth
    const offenders = []
    for (const element of document.querySelectorAll('body *')) {
      const node = element
      const style = getComputedStyle(node)
      if (style.position === 'fixed' || style.display === 'none' || style.visibility === 'hidden') {
        continue
      }
      const rect = node.getBoundingClientRect()
      if (rect.width <= 0 || rect.height <= 0) continue
      // 允许 1px 的亚像素误差
      if (rect.right > vw + 1 || rect.left < -1) {
        offenders.push({
          tag: node.tagName.toLowerCase(),
          className: typeof node.className === 'string' ? node.className.slice(0, 80) : '',
          left: Math.round(rect.left),
          right: Math.round(rect.right),
        })
      }
    }
    return {
      viewportWidth: vw,
      innerWidth: window.innerWidth,
      scrollWidth: document.documentElement.scrollWidth,
      offenders: offenders.slice(0, 8),
    }
  })
  assert(
    result.viewportWidth === 375,
    `${label}: 布局视口为 375px（实际 ${result.viewportWidth}）`,
  )
  assert(
    result.scrollWidth <= result.viewportWidth + 1,
    `${label}: 无横向溢出（scrollWidth=${result.scrollWidth}, clientWidth=${result.viewportWidth}, innerWidth=${result.innerWidth}）`,
  )
  if (result.offenders.length) {
    console.error('    越界元素:', JSON.stringify(result.offenders))
  }
  assert(result.offenders.length === 0, `${label}: 没有越界的可见元素`)
}

/** 滚动到可见后检查按钮中心点，返回可点击的坐标；被底部导航/顶栏遮住则记录失败 */
async function assertButtonClickable(locator, label) {
  await locator.scrollIntoViewIfNeeded()
  await page.waitForTimeout(180)
  const box = await locator.boundingBox()
  assert(Boolean(box), `${label}: 按钮存在且有尺寸`)
  if (!box) return null
  const x = box.x + box.width / 2
  const y = box.y + box.height / 2
  const covered = await page.evaluate(
    ({ pointX, pointY }) => {
      const top = document.elementFromPoint(pointX, pointY)
      if (!top) return 'none'
      if (top.closest('.mobile-nav')) return 'mobile-nav'
      if (top.closest('.topbar')) return 'topbar'
      return ''
    },
    { pointX: x, pointY: y },
  )
  assert(covered === '', `${label}: 按钮中心未被遮挡${covered ? `（被 ${covered} 覆盖）` : ''}`)
  return covered === '' ? { x, y } : null
}

/**
 * 用真实鼠标点击已验证可点击的坐标。
 *
 * 【为什么不用 locator.click()】本应用的路由切换使用 smooth 滚动，Playwright 的自动
 * scrollIntoView 可能在动画未结束时反复重算位置，导致"按钮明明可见却点不中"的测试假阴性。
 * 这里先显式滚动 + 用 elementFromPoint 验证遮挡，再按真实坐标点击，既保留可点击性断言，
 * 又不被动画时序干扰。
 */
async function clickVerified(locator, label) {
  const point = await assertButtonClickable(locator, label)
  if (!point) return false
  await page.mouse.click(point.x, point.y)
  return true
}

try {
  console.log(`[mobile] baseURL=${baseURL}`)

  // 1) 登录页：375px 下表单可操作且不横向溢出
  await page.goto('/login', { waitUntil: 'networkidle' })
  await assertNoHorizontalOverflow('登录页')
  await page.getByPlaceholder('请输入用户名').fill('Jack')
  await page.getByPlaceholder('请输入登录密码').fill('123456')
  await page.getByRole('button', { name: '进入 LLMall' }).click()
  await page.waitForURL(/\/feed/, { timeout: 20_000 })
  await assertNoHorizontalOverflow('动态页')

  // 2) 商城：首屏 + 横向溢出 + 加购按钮可点击
  await page.goto('/mall', { waitUntil: 'networkidle' })
  const firstCard = page.getByTestId('mall-card').first()
  await firstCard.waitFor({ state: 'visible', timeout: 20_000 })
  const addedItemId = await firstCard.getAttribute('data-item-id')
  await assertNoHorizontalOverflow('商城页')
  // 截图取页首（工具栏 + 商品卡片），再滚动到加购按钮做可点击性检查
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'auto' }))
  await page.waitForTimeout(300)
  await page.screenshot({ path: resolve(outDir, 'mobile-mall.png'), fullPage: false })
  await assertButtonClickable(
    firstCard.getByRole('button', { name: '加入购物车' }),
    '商城加购按钮',
  )

  // 3) 购物车：加购一件后检查行布局与「去结算」按钮
  const [addResponse] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes('/api/carts') && response.request().method() === 'POST',
    ),
    clickVerified(firstCard.getByRole('button', { name: '加入购物车' }), '商城加购按钮'),
  ])
  assert(addResponse.ok(), `商城加购接口返回 200（实际 ${addResponse.status()}）`)

  await page.goto('/cart', { waitUntil: 'networkidle' })
  const cartRow = page.getByTestId('cart-row').first()
  await cartRow.waitFor({ state: 'visible', timeout: 20_000 })
  await assertNoHorizontalOverflow('购物车页')
  await assertButtonClickable(page.getByRole('button', { name: /去结算/ }), '购物车去结算按钮')

  // 4) 门店详情 + 评价区：截图滚动到评价区，验证头像/昵称/评分在 375px 下不溢出
  await page.goto('/shops/10', { waitUntil: 'networkidle' })
  await page.getByTestId('review-section').waitFor({ state: 'visible', timeout: 20_000 })
  await assertNoHorizontalOverflow('门店详情页')
  await assertButtonClickable(page.getByTestId('write-review'), '门店写评价按钮')
  // 定位到评价区并把标题留在 sticky 顶栏下方（scrollIntoView 会正好顶到顶栏后面）
  await page.evaluate(() => {
    const section = document.querySelector('#reviews')
    if (!section) return
    const top = section.getBoundingClientRect().top + window.scrollY - 74
    window.scrollTo({ top, behavior: 'auto' })
  })
  await page.waitForTimeout(300)
  const firstReview = page.getByTestId('review-item').first()
  if (await firstReview.count()) {
    const avatarBox = await firstReview.getByTestId('review-avatar').boundingBox()
    assert(Boolean(avatarBox), '评价头像可见且有尺寸')
  }
  await page.screenshot({ path: resolve(outDir, 'mobile-shop.png'), fullPage: false })

  // 5) 写评价页：375px 下表单完整、提交按钮可点击
  // 先回到页首再定位按钮：刚截过评价区，按钮位于 sticky 顶栏上方，直接 scrollIntoView 会停在顶栏后面。
  // 真实用户同样会先向上滚到「门店评价」标题处再点；这里用页首作为确定性的起点。
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'auto' }))
  await page.waitForTimeout(300)
  const navigated = await clickVerified(page.getByTestId('write-review'), '门店写评价按钮')
  assert(navigated, '门店写评价按钮可点击')
  await page.waitForURL(/\/shops\/10\/review/, { timeout: 15_000 })
  await page.getByTestId('review-form').waitFor({ state: 'visible', timeout: 15_000 })
  await assertNoHorizontalOverflow('写评价页')
  await assertButtonClickable(page.getByTestId('review-submit'), '发布评价按钮')
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'auto' }))
  await page.waitForTimeout(300)
  await page.screenshot({ path: resolve(outDir, 'mobile-review-form.png'), fullPage: false })

  // 清理：只移除本脚本加购的那件商品，避免影响后续手工体验
  const token = await page.evaluate(() => window.localStorage.getItem('llmall.accessToken') || '')
  if (token && addedItemId) {
    const listResponse = await context.request.get(`${baseURL}/api/carts`, {
      headers: { authorization: token },
    })
    if (listResponse.ok()) {
      const rows = await listResponse.json()
      for (const row of Array.isArray(rows) ? rows : []) {
        if (String(row.itemId) !== String(addedItemId)) continue
        await context.request.delete(`${baseURL}/api/carts/${row.id}`, {
          headers: { authorization: token },
        })
      }
    }
  }
} finally {
  await browser.close()
}

if (failures.length) {
  console.error(`\n[mobile] 失败 ${failures.length} 项`)
  process.exit(1)
}
console.log('\n[mobile] 全部通过，截图已写入 docs/images/')
