import { expect, test } from '@playwright/test'
import { pickCheapestPackage } from './support/api'
import { loginWithUi, uniqueSuffix } from './support/ui'

/**
 * 冒烟 1 · 新增地址
 *
 * 登录 → 打开 10 号店某套餐的确认订单页 → 新增地址 → 保存 → 断言地址出现在收货地址列表。
 * 可重复跑：每次都用唯一联系人/详细地址新建一条，不依赖库里原有地址。
 */
test('新增地址后出现在收货地址列表', async ({ page, request }) => {
  await loginWithUi(page)

  // 套餐 id 动态取（写死会在换库/换种子后失效），门店固定为演示账号任职的 10 号店
  const item = await pickCheapestPackage(request, '10')
  await page.goto(`/checkout?itemId=${item.id}&shopId=10&num=1`)

  const addAddressButton = page.getByRole('button', { name: '新增地址' }).first()
  await expect(addAddressButton, '确认订单页应能打开并展示"新增地址"入口').toBeVisible()

  const suffix = uniqueSuffix()
  const contact = `E2E${suffix}`
  const street = `E2E 测试路 ${suffix} 号`

  await addAddressButton.click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '新增收货地址' })
  await expect(dialog).toBeVisible()

  await dialog.getByPlaceholder('收货人姓名').fill(contact)
  await dialog.getByPlaceholder('11 位手机号').fill('13800001111')
  await dialog.getByPlaceholder('街道、门牌号、楼层等').fill(street)
  await dialog.getByRole('button', { name: '保存并使用' }).click()

  // 保存成功的即时反馈（ElMessage）+ 列表里真的多出这条地址
  await expect(page.locator('.el-message').filter({ hasText: '收货地址已添加' })).toBeVisible()
  const addressList = page.locator('.address-list')
  await expect(addressList.getByText(contact), '新地址应出现在地址列表中').toBeVisible()
  await expect(addressList.getByText(street)).toBeVisible()

  // 新地址应被自动选中（保存并使用），这是"加完就能直接下单"的关键体验
  await expect(
    page.locator('.address-option.active').filter({ hasText: contact }),
    '保存后新地址应处于选中状态',
  ).toHaveCount(1)
})
