<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  ArrowLeft,
  Check,
  Location,
  Lock,
  Plus,
  Ticket,
  Wallet,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import { addressApi, itemApi, orderApi, payApi, shopApi, voucherApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { Address, Id, Item, Order, Shop, UserVoucher } from '@/api/types'
import {
  formatDate,
  formatMoney,
  formatPrice,
  formatSpec,
  initialOf,
  isExpired,
  uuid,
} from '@/utils/format'

interface AddressForm {
  province: string
  city: string
  town: string
  contact: string
  mobile: string
  street: string
  isDefault: number
}

/**
 * 结算行：立即购买只有 1 行；购物车结算可有多行。
 *
 * 【为什么以 item 详情为主】购物车只存加入时的价格/名称，结算页必须用商品当前价格与库存，
 * 否则后端按下单价计算、前端按旧价展示，付款金额会和用户看到的不一致。
 */
interface CheckoutLine {
  itemId: Id
  num: number
  item: Item
  /** 来自购物车时有值；立即购买没有 */
  cartId?: Id
}

/** 购物车「去结算」写入 sessionStorage 的键，与 Cart.vue 必须一致 */
const CHECKOUT_STORAGE_KEY = 'llmall.checkout.lines'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const loading = ref(true)
const errorMessage = ref('')
/** 主商品：单商品结算时就是那一件；多商品时取第一件，用于兼容既有页面文案/摘要 */
const item = ref<Item | null>(null)
const lines = ref<CheckoutLine[]>([])
const shop = ref<Shop | null>(null)

const addresses = ref<Address[]>([])
const addressError = ref('')
const selectedAddressId = ref('')

const myVouchers = ref<UserVoucher[]>([])
const selectedVoucherId = ref('')

const submitting = ref(false)
const applying = ref(false)
const paying = ref(false)
const step = ref<'confirm' | 'pay' | 'success'>('confirm')
const order = ref<Order | null>(null)
const payOrderId = ref('')
const payError = ref('')
const payPassword = ref('')
const clientToken = ref(uuid())
let redirectTimer: number | undefined

const addressDialog = ref(false)
const addressSaving = ref(false)
const addressFormRef = ref<FormInstance>()
const addressForm = reactive<AddressForm>({
  province: '浙江省',
  city: '杭州市',
  town: '西湖区',
  contact: '',
  mobile: '',
  street: '',
  isDefault: 1,
})
const addressRules: FormRules = {
  contact: [{ required: true, message: '请输入联系人', trigger: 'blur' }],
  mobile: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  street: [{ required: true, message: '请输入详细地址', trigger: 'blur' }],
}

const itemId = computed(() => {
  return typeof route.query.itemId === 'string' ? route.query.itemId : ''
})

const goodsTotal = computed(() =>
  lines.value.reduce(
    (sum, line) => sum + Number(line.item.price || 0) * Number(line.num || 0),
    0,
  ),
)

/** 多商品来自不同门店时为空：此时门店券不可用，只能用无门店限制的券 */
const commonShopId = computed(() => {
  const ids = Array.from(
    new Set(lines.value.map((line) => line.item.shopId).filter((id): id is Id => Boolean(id))),
  )
  return ids.length === 1 ? ids[0] : ''
})

/** 购物车行跨多个 shopId：结算只能使用通用券（shopId 为空），页面需要明确提示降级原因 */
const multiShop = computed(() => {
  const ids = new Set(
    lines.value
      .map((line) => line.item.shopId)
      .filter((id): id is Id => Boolean(id))
      .map(String),
  )
  return ids.size > 1
})

const itemSoldOut = computed(() =>
  lines.value.some(
    (line) => Number(line.item.stock ?? 0) <= 0 || Number(line.num) > Number(line.item.stock ?? 0),
  ),
)

function maxQtyOf(line: CheckoutLine): number {
  return Math.max(1, Number(line.item.stock || 1))
}

const availableVouchers = computed(() => {
  return myVouchers.value.filter((voucher) => {
    if (Number(voucher.status) !== 1) return false
    if (isExpired(voucher.expireTime)) return false
    // 通用券（shopId 为空）在任何结算组合下都可用
    if (!voucher.shopId) return true
    // 多门店结算只展示通用券：门店券无法同时覆盖多个 shopId，后端也没有"跨店券"模型
    if (multiShop.value) return false
    if (!commonShopId.value) return false
    return String(voucher.shopId) === String(commonShopId.value)
  })
})

const selectedVoucher = computed(() => {
  return availableVouchers.value.find((voucher) => String(voucher.id) === selectedVoucherId.value) || null
})

const discount = computed(() => {
  return Math.min(goodsTotal.value, Number(selectedVoucher.value?.actualValue || 0))
})

const estimatedTotal = computed(() => Math.max(0, goodsTotal.value - discount.value))
const selectedAddress = computed(() => {
  return addresses.value.find((address) => String(address.id) === selectedAddressId.value) || null
})
const orderTotal = computed(() => Number(order.value?.totalFee ?? estimatedTotal.value))
const balanceEnough = computed(() => auth.balance >= orderTotal.value)
const stepActive = computed(() => (step.value === 'confirm' ? 0 : step.value === 'pay' ? 1 : 2))
const emptyPage = computed(() => !loading.value && !errorMessage.value && !lines.value.length)

function clampNum(value: unknown, stock: unknown): number {
  const requestNum = Number(value)
  const safeNum = Number.isFinite(requestNum) ? requestNum : 1
  return Math.min(Math.max(1, Math.floor(safeNum)), Math.max(1, Number(stock || 1)))
}

function readCartLines(): Array<{ itemId: Id; num: number; cartId?: Id }> {
  const raw = sessionStorage.getItem(CHECKOUT_STORAGE_KEY)
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as Array<{ itemId?: string; num?: number; cartId?: string }>
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((entry) => entry && entry.itemId)
      .map((entry) => ({
        itemId: String(entry.itemId),
        num: Number(entry.num || 1),
        ...(entry.cartId ? { cartId: String(entry.cartId) } : {}),
      }))
  } catch {
    return []
  }
}

/** 地址与可用券的加载：两种结算来源共用，失败不阻断商品展示 */
async function loadSupportData(): Promise<void> {
  const [addressResult, voucherResult] = await Promise.allSettled([
    addressApi.list(),
    voucherApi.my(1),
  ])

  if (addressResult.status === 'fulfilled') {
    addresses.value = Array.isArray(addressResult.value) ? addressResult.value : []
    const preferred =
      addresses.value.find((address) => Number(address.isDefault) === 1) || addresses.value[0]
    selectedAddressId.value = preferred ? String(preferred.id) : ''
  } else {
    addressError.value = getErrorMessage(addressResult.reason, '收货地址加载失败')
  }

  if (voucherResult.status === 'fulfilled') {
    myVouchers.value = Array.isArray(voucherResult.value) ? voucherResult.value : []
  } else {
    myVouchers.value = []
  }
}

/**
 * 加载结算商品，兼容两种入口：
 * 1) 立即购买：/checkout?itemId=xxx&num=1 —— 按接口取最新商品，单行结算；
 * 2) 购物车结算：sessionStorage.llmall.checkout.lines —— 选中条目 [{itemId,num,cartId}]，
 *    再按 itemId 批量取最新价格/库存；读后即清，避免刷新/回退后重复结算同一批。
 */
async function loadPage(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  addressError.value = ''
  selectedVoucherId.value = ''
  lines.value = []
  item.value = null
  shop.value = null
  try {
    if (itemId.value) {
      const detail = await itemApi.byId(itemId.value)
      const num = clampNum(route.query.num ?? 1, detail.stock)
      lines.value = [{ itemId: detail.id, num, item: detail }]
      item.value = detail
    } else {
      const stored = readCartLines()
      if (!stored.length) {
        loading.value = false
        return
      }
      const details = await itemApi.byIds(stored.map((line) => line.itemId))
      const byId = new Map(details.map((detail) => [String(detail.id), detail]))
      const next: CheckoutLine[] = []
      stored.forEach((line) => {
        const detail = byId.get(String(line.itemId))
        if (!detail) return
        next.push({
          itemId: detail.id,
          // 购物车数量可能大于当前库存：收敛到库存，下单时由后端再次校验
          num: clampNum(line.num, detail.stock),
          item: detail,
          ...(line.cartId ? { cartId: line.cartId } : {}),
        })
      })
      if (!next.length) {
        throw new Error('购物车中的商品已失效，请返回购物车重新选择')
      }
      lines.value = next
      item.value = next[0].item
      sessionStorage.removeItem(CHECKOUT_STORAGE_KEY)
    }

    await loadSupportData()

    if (commonShopId.value) {
      try {
        shop.value = await shopApi.detail(commonShopId.value)
      } catch {
        shop.value = null
      }
    }
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '商品信息加载失败')
  } finally {
    loading.value = false
  }
}

/** 结算页内改数量：只影响本页（购物车数量不在这里回写，避免用户只是改结算数量却动了购物车） */
function changeLineNum(line: CheckoutLine, value: number | undefined): void {
  const next = Number(value || 0)
  line.num = next > 0 ? Math.min(Math.floor(next), maxQtyOf(line)) : 1
}

function openAddressDialog(): void {
  addressForm.contact = ''
  addressForm.mobile = ''
  addressForm.street = ''
  addressDialog.value = true
}

async function saveAddress(): Promise<void> {
  if (!addressFormRef.value) return
  const valid = await addressFormRef.value.validate().catch(() => false)
  if (!valid) return
  addressSaving.value = true
  try {
    const id = await addressApi.create({ ...addressForm })
    const latest = await addressApi.list()
    addresses.value = latest
    selectedAddressId.value = String(id)
    addressDialog.value = false
    ElMessage.success('收货地址已添加')
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '地址保存失败'))
  } finally {
    addressSaving.value = false
  }
}

async function reloadAddresses(): Promise<void> {
  addressError.value = ''
  try {
    addresses.value = await addressApi.list()
    if (!selectedAddressId.value && addresses.value.length) {
      const preferred =
        addresses.value.find((address) => Number(address.isDefault) === 1) || addresses.value[0]
      selectedAddressId.value = String(preferred.id)
    }
  } catch (error) {
    addressError.value = getErrorMessage(error, '收货地址加载失败')
  }
}

async function submitOrder(): Promise<void> {
  if (!lines.value.length || submitting.value) return
  if (!selectedAddressId.value) {
    ElMessage.warning('请选择或新增收货地址')
    return
  }
  if (itemSoldOut.value) {
    ElMessage.warning('部分商品库存不足，请调整数量或返回购物车')
    return
  }

  submitting.value = true
  payError.value = ''
  let createdOrderId = ''
  try {
    const payload = {
      addressId: selectedAddressId.value,
      paymentType: 3,
      // 立即购买与购物车结算共用同一条多行 details；单商品时就是长度为 1 的数组
      details: lines.value.map((line) => ({
        itemId: line.itemId,
        num: Number(line.num),
      })),
      clientToken: clientToken.value,
      ...(selectedVoucherId.value ? { userVoucherId: selectedVoucherId.value } : {}),
    }
    createdOrderId = await orderApi.create(payload)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '下单失败，请稍后重试'))
    submitting.value = false
    return
  }

  try {
    order.value = await orderApi.detail(createdOrderId)
  } catch {
    ElMessage.warning('订单已创建，请前往订单页查看并支付')
    submitting.value = false
    await router.push('/orders')
    return
  }

  sessionStorage.removeItem(CHECKOUT_STORAGE_KEY)
  step.value = 'pay'
  submitting.value = false
  window.scrollTo({ top: 0, behavior: 'smooth' })
  await preparePayment()
}

async function preparePayment(): Promise<void> {
  if (!order.value || applying.value) return
  applying.value = true
  payError.value = ''
  try {
    const orderInfo =
      (order.value.details || []).map((detail) => `${detail.name}×${detail.num}`).join(';') ||
      lines.value.map((line) => `${line.item.name}×${line.num}`).join(';') ||
      `${item.value?.name || '商品'}×1`
    const id = await payApi.apply({
      bizOrderNo: order.value.id,
      amount: Number(order.value.totalFee),
      payChannelCode: 'balance',
      payType: 5,
      orderInfo,
    })
    payOrderId.value = String(id || '')
    if (!payOrderId.value) {
      throw new Error('支付单创建失败')
    }
  } catch (error) {
    payError.value = getErrorMessage(error, '支付单创建失败，请重试')
  } finally {
    applying.value = false
  }
}

async function waitForOrderPaid(): Promise<void> {
  if (!order.value) return
  for (let index = 0; index < 8; index += 1) {
    await new Promise((resolve) => window.setTimeout(resolve, 700))
    try {
      const latest = await orderApi.detail(order.value.id)
      order.value = latest
      if (Number(latest.status) !== 1) return
    } catch {
      // 支付状态由 MQ 异步同步，单次轮询失败继续等待即可
    }
  }
}

async function payNow(): Promise<void> {
  if (paying.value || applying.value) return
  if (!payOrderId.value) {
    await preparePayment()
    if (!payOrderId.value) return
  }
  const pw = payPassword.value.trim()
  if (!pw) {
    ElMessage.warning('请输入支付口令')
    return
  }

  paying.value = true
  try {
    await payApi.payByBalance(payOrderId.value, pw)
    ElMessage.success('支付成功')
    await waitForOrderPaid()
    await auth.fetchMe().catch(() => {
      // 余额展示稍后刷新即可，不影响支付结果
    })
    step.value = 'success'
    redirectTimer = window.setTimeout(() => {
      void router.replace('/orders')
    }, 1600)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '支付失败，请检查支付口令或余额'))
  } finally {
    paying.value = false
  }
}

function goOrders(): void {
  if (redirectTimer) window.clearTimeout(redirectTimer)
  void router.replace('/orders')
}

function goShops(): void {
  void router.push('/shops')
}

onMounted(() => {
  void loadPage()
})

onBeforeUnmount(() => {
  if (redirectTimer) window.clearTimeout(redirectTimer)
})
</script>

<template>
  <StateBlock
    :loading="loading"
    :error="errorMessage"
    :empty="emptyPage"
    empty-text="还没有选择商品，先去门店挑一份套餐吧"
    @retry="loadPage"
  >
    <template #empty-action>
      <el-button type="primary" @click="goShops">去逛门店</el-button>
    </template>

    <div v-if="item" class="checkout-page">
      <div class="checkout-top">
        <el-button text :icon="ArrowLeft" @click="router.back()">返回</el-button>
        <div class="checkout-steps">
          <div class="checkout-step" :class="{ done: stepActive >= 0, current: stepActive === 0 }">
            <span class="step-index">
              <el-icon v-if="stepActive > 0"><Check /></el-icon>
              <template v-else>1</template>
            </span>
            <span>确认订单</span>
          </div>
          <i></i>
          <div class="checkout-step" :class="{ done: stepActive >= 1, current: stepActive === 1 }">
            <span class="step-index">
              <el-icon v-if="stepActive > 1"><Check /></el-icon>
              <template v-else>2</template>
            </span>
            <span>余额支付</span>
          </div>
          <i></i>
          <div class="checkout-step" :class="{ done: stepActive >= 2, current: stepActive === 2 }">
            <span class="step-index">3</span>
            <span>完成</span>
          </div>
        </div>
      </div>

      <template v-if="step === 'confirm'">
        <PageHeader
          eyebrow="CHECKOUT"
          title="确认订单"
          subtitle="核对商品、收货地址与优惠券，提交后将锁定库存并进入余额支付。"
        />

        <div class="checkout-layout">
          <div class="checkout-main">
            <section class="panel checkout-panel">
              <div class="panel-head">
                <div>
                  <h2><el-icon class="h2-icon"><Location /></el-icon>收货信息</h2>
                  <p>选择一个地址，或新增常用地址。</p>
                </div>
                <el-button :icon="Plus" size="small" @click="openAddressDialog">新增地址</el-button>
              </div>

              <el-alert
                v-if="addressError"
                class="panel-alert"
                type="error"
                :closable="false"
                show-icon
                :title="addressError"
              >
                <template #default>
                  <el-button link type="primary" @click="reloadAddresses">重新加载地址</el-button>
                </template>
              </el-alert>

              <div v-if="addresses.length" class="address-list">
                <button
                  v-for="address in addresses"
                  :key="address.id"
                  type="button"
                  class="address-option"
                  :class="{ active: selectedAddressId === String(address.id) }"
                  @click="selectedAddressId = String(address.id)"
                >
                  <div class="address-head">
                    <b>{{ address.contact }}</b>
                    <span>{{ address.mobile }}</span>
                    <el-tag v-if="Number(address.isDefault) === 1" size="small" type="primary">
                      默认
                    </el-tag>
                  </div>
                  <p>
                    {{ address.province }}{{ address.city }}{{ address.town
                    }}{{ address.street }}
                  </p>
                  <span v-if="selectedAddressId === String(address.id)" class="address-check">
                    <el-icon><Check /></el-icon>
                  </span>
                </button>
              </div>
              <el-empty
                v-else-if="!addressError"
                description="还没有收货地址，添加一个即可继续下单"
                :image-size="72"
              >
                <el-button type="primary" :icon="Plus" @click="openAddressDialog">新增地址</el-button>
              </el-empty>
            </section>

            <section class="panel checkout-panel">
              <div class="panel-head">
                <div>
                  <h2>商品清单</h2>
                  <p v-if="shop">
                    <router-link
                      class="shop-name-link"
                      :to="{ name: 'shop-detail', params: { id: shop.id } }"
                    >
                      {{ shop.name }}
                    </router-link>
                  </p>
                  <p v-else-if="lines.length > 1">多门店商品合并结算</p>
                </div>
                <span class="panel-badge">{{ lines.length }} 种商品 · 余额支付</span>
              </div>

              <div
                v-for="line in lines"
                :key="line.itemId"
                class="goods-row"
                data-testid="checkout-line"
                :data-item-id="line.itemId"
              >
                <div class="goods-thumb">
                  <img
                    v-if="line.item.image"
                    :src="line.item.image"
                    :alt="line.item.name"
                    @error="($event.target as HTMLImageElement).style.display = 'none'"
                  />
                  <span class="thumb-initial">{{ initialOf(line.item.name) }}</span>
                </div>
                <div class="goods-info">
                  <h3>{{ line.item.name }}</h3>
                  <p>{{ formatSpec(line.item.spec) || line.item.category || '到店核销 · 过期自动退' }}</p>
                  <div class="goods-tags">
                    <span>库存 {{ line.item.stock }}</span>
                    <span>已售 {{ line.item.sold }}</span>
                  </div>
                </div>
                <div class="goods-buy">
                  <span class="goods-price">{{ formatPrice(line.item.price) }}</span>
                  <el-input-number
                    v-model="line.num"
                    :min="1"
                    :max="maxQtyOf(line)"
                    size="small"
                    :disabled="Number(line.item.stock) <= 0"
                    @change="(value: number | undefined) => changeLineNum(line, value)"
                  />
                </div>
              </div>
            </section>

            <section class="panel checkout-panel">
              <div class="panel-head">
                <div>
                  <h2><el-icon class="h2-icon"><Ticket /></el-icon>优惠券</h2>
                  <p>
                    {{
                      multiShop
                        ? '多门店结算仅展示通用券（无门店限制）。'
                        : '仅展示当前门店可用、未过期的券。'
                    }}
                  </p>
                </div>
                <span class="panel-badge">{{ availableVouchers.length }} 张可用</span>
              </div>

              <div
                v-if="multiShop"
                class="multi-shop-hint"
                data-testid="multi-shop-voucher-hint"
              >
                <el-alert
                  type="warning"
                  :closable="false"
                  show-icon
                  title="多门店结算仅支持通用券"
                  description="购物车商品来自多个门店，本页只展示无门店限制的通用券；门店券请在单店结算时使用。"
                />
              </div>

              <div v-if="availableVouchers.length" class="voucher-options">
                <button
                  type="button"
                  class="voucher-option none"
                  :class="{ active: !selectedVoucherId }"
                  @click="selectedVoucherId = ''"
                >
                  <span class="vo-title">不使用优惠券</span>
                  <el-icon v-if="!selectedVoucherId"><Check /></el-icon>
                </button>
                <button
                  v-for="voucher in availableVouchers"
                  :key="voucher.id"
                  type="button"
                  class="voucher-option"
                  :class="{ active: selectedVoucherId === String(voucher.id) }"
                  @click="selectedVoucherId = String(voucher.id)"
                >
                  <span class="vo-value">{{ formatPrice(voucher.actualValue) }}</span>
                  <span class="vo-body">
                    <b>{{ voucher.title }}</b>
                    <small>有效期至 {{ formatDate(voucher.expireTime) }}</small>
                  </span>
                  <el-icon v-if="selectedVoucherId === String(voucher.id)"><Check /></el-icon>
                </button>
              </div>
              <div v-else class="voucher-none">
                {{ multiShop ? '本单暂无可用通用券' : '本店暂无可用于本单的优惠券' }}
              </div>
            </section>
          </div>

          <aside class="summary-card">
            <div class="summary-head">
              <span>订单金额</span>
              <el-tag size="small" type="success" effect="light">待支付</el-tag>
            </div>
            <div class="summary-total">{{ formatMoney(estimatedTotal) }}</div>
            <p class="summary-hint">{{ discount > 0 ? '已抵扣优惠券' : '未使用优惠券' }}</p>

            <div class="summary-rows">
              <div>
                <span>商品金额</span>
                <b>{{ formatMoney(goodsTotal) }}</b>
              </div>
              <div v-if="discount > 0">
                <span>优惠券抵扣</span>
                <b class="discount">-{{ formatMoney(discount) }}</b>
              </div>
              <div>
                <span>支付方式</span>
                <b>余额支付</b>
              </div>
              <div>
                <span>账户余额</span>
                <b>{{ formatMoney(auth.balance) }}</b>
              </div>
            </div>

            <el-alert
              v-if="selectedAddress && !balanceEnough"
              class="balance-alert"
              type="warning"
              :closable="false"
              show-icon
              title="账户余额可能不足"
              description="支付时以后端余额为准，若不足请先充值或选择其他订单。"
            />

            <el-button
              class="submit-order"
              type="primary"
              size="large"
              :loading="submitting"
              :disabled="!selectedAddressId || itemSoldOut"
              @click="submitOrder"
            >
              提交订单并去支付
            </el-button>
            <p class="summary-note">
              提交后库存将锁定，请在 15 分钟内完成支付；超时订单会自动关闭并回滚库存。
            </p>
          </aside>
        </div>
      </template>

      <template v-else-if="step === 'pay' && order">
        <PageHeader
          eyebrow="CASHIER"
          title="收银台"
          subtitle="订单已创建，请使用账户余额完成支付。"
        />

        <div class="pay-layout">
          <section class="pay-card">
            <div class="pay-head">
              <span class="pay-icon"><el-icon><Wallet /></el-icon></span>
              <div>
                <b>余额支付</b>
                <span>订单号 {{ order.id }}</span>
              </div>
              <el-tag type="warning" effect="light" size="small">待支付</el-tag>
            </div>

            <div class="pay-amount">{{ formatMoney(orderTotal) }}</div>
            <p class="pay-amount-label">应付金额（元）</p>

            <div class="pay-balance">
              <span>当前账户余额</span>
              <b>{{ formatMoney(auth.balance) }}</b>
            </div>

            <el-alert
              v-if="auth.user?.needChangePayPassword"
              class="pay-alert"
              type="warning"
              :closable="false"
              show-icon
              title="当前仍在使用初始支付口令"
              description="余额支付要求先修改支付口令，否则会被服务端拒绝；请先完成支付口令修改后再试。"
            />
            <el-alert
              v-else-if="!balanceEnough"
              class="pay-alert"
              type="warning"
              :closable="false"
              show-icon
              title="账户余额可能不足"
              description="如果支付失败，请确认余额后重试。"
            />

            <el-alert
              v-if="payError"
              class="pay-alert"
              type="error"
              :closable="false"
              show-icon
              :title="payError"
            >
              <template #default>
                <el-button link type="primary" :loading="applying" @click="preparePayment">
                  重新申请支付单
                </el-button>
              </template>
            </el-alert>

            <label class="pay-label">支付口令</label>
            <p class="pay-demo-tip">演示环境支付口令为 <code>888888</code>（与登录密码不同）</p>
            <el-input
              v-model="payPassword"
              size="large"
              type="password"
              show-password
              :prefix-icon="Lock"
              placeholder="请输入支付口令（演示支付口令：888888）"
              @keyup.enter="payNow"
            />

            <el-button
              class="pay-submit"
              type="primary"
              size="large"
              :loading="paying"
              :disabled="applying || !payOrderId"
              @click="payNow"
            >
              <el-icon v-if="!paying"><Wallet /></el-icon>
              确认支付 {{ formatMoney(orderTotal) }}
            </el-button>

            <el-button class="pay-later" link @click="goOrders">稍后支付，查看订单</el-button>

            <p class="pay-footnote">
              支付链路：创建支付单 → 校验支付单状态 → 扣减余额 → 支付成功 → 订单状态异步同步。
            </p>
          </section>

          <aside class="pay-summary">
            <div class="pay-summary-head">
              <h3>订单摘要</h3>
              <router-link
                v-if="shop"
                :to="{ name: 'shop-detail', params: { id: shop.id } }"
                class="shop-name-link"
              >
                {{ shop.name }}
              </router-link>
            </div>

            <div
              v-for="line in lines"
              :key="line.itemId"
              class="pay-goods"
            >
              <div class="pay-goods-thumb">
                <img v-if="line.item.image" :src="line.item.image" :alt="line.item.name" />
                <span v-else>{{ initialOf(line.item.name) }}</span>
              </div>
              <div class="pay-goods-info">
                <b>{{ line.item.name }}</b>
                <span>{{ formatPrice(line.item.price) }} × {{ line.num }}</span>
              </div>
              <b class="pay-goods-price">
                {{ formatMoney(Number(line.item.price) * Number(line.num)) }}
              </b>
            </div>

            <div class="pay-summary-rows">
              <div>
                <span>收货人</span>
                <b>{{ selectedAddress?.contact || '—' }}</b>
              </div>
              <div>
                <span>联系电话</span>
                <b>{{ selectedAddress?.mobile || '—' }}</b>
              </div>
              <div>
                <span>收货地址</span>
                <b class="address-text">
                  {{ selectedAddress ? `${selectedAddress.province}${selectedAddress.city}${selectedAddress.town}${selectedAddress.street}` : '—' }}
                </b>
              </div>
              <div>
                <span>优惠券</span>
                <b>{{ selectedVoucher?.title || '未使用' }}</b>
              </div>
              <div>
                <span>创建时间</span>
                <b>{{ formatDate(order.createTime) }}</b>
              </div>
            </div>

            <div class="pay-summary-total">
              <span>应付金额</span>
              <b>{{ formatMoney(orderTotal) }}</b>
            </div>
          </aside>
        </div>
      </template>

      <div v-else-if="order" class="success-wrap">
        <el-result
          icon="success"
          title="支付成功"
          :sub-title="`订单 ${order.id} 已完成支付，正在前往订单页…`"
        >
          <template #extra>
            <el-button type="primary" @click="goOrders">查看订单</el-button>
            <el-button @click="goShops">继续逛逛</el-button>
          </template>
        </el-result>
      </div>
    </div>
  </StateBlock>

  <el-dialog
    v-model="addressDialog"
    title="新增收货地址"
    width="560px"
    :close-on-click-modal="false"
    append-to-body
  >
    <el-form
      ref="addressFormRef"
      :model="addressForm"
      :rules="addressRules"
      label-position="top"
    >
      <div class="address-form-grid">
        <el-form-item label="联系人" prop="contact">
          <el-input v-model="addressForm.contact" placeholder="收货人姓名" maxlength="20" />
        </el-form-item>
        <el-form-item label="手机号" prop="mobile">
          <el-input v-model="addressForm.mobile" placeholder="11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="省">
          <el-input v-model="addressForm.province" maxlength="10" />
        </el-form-item>
        <el-form-item label="市">
          <el-input v-model="addressForm.city" maxlength="10" />
        </el-form-item>
        <el-form-item label="区 / 县">
          <el-input v-model="addressForm.town" maxlength="10" />
        </el-form-item>
        <el-form-item label="详细地址" prop="street" class="full">
          <el-input
            v-model="addressForm.street"
            placeholder="街道、门牌号、楼层等"
            maxlength="255"
          />
        </el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="addressDialog = false">取消</el-button>
      <el-button type="primary" :loading="addressSaving" @click="saveAddress">保存并使用</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.checkout-page {
  width: 100%;
}

.checkout-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.checkout-top :deep(.el-button) {
  color: var(--muted);
}

.checkout-steps {
  display: flex;
  align-items: center;
  gap: 8px;
}

.checkout-step {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--faint);
  font-size: 11.5px;
  font-weight: 600;
  white-space: nowrap;
}

.checkout-steps i {
  width: 20px;
  height: 1px;
  background: var(--line-strong);
}

.step-index {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #eef5ff;
  color: var(--faint);
  font-size: 10.5px;
  font-weight: 700;
}

.step-index .el-icon {
  font-size: 12px;
}

.checkout-step.done {
  color: var(--brand-600);
}

.checkout-step.done .step-index {
  background: var(--brand-100);
  color: var(--brand-600);
}

.checkout-step.current {
  color: var(--brand-700);
}

.checkout-step.current .step-index {
  background: var(--brand-500);
  color: #fff;
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.12);
}

.checkout-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  align-items: start;
  gap: 22px;
}

.checkout-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.checkout-panel {
  padding: 20px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 15px;
}

.panel-head h2 {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0;
  font-size: 16px;
  font-weight: 750;
  letter-spacing: -0.01em;
}

.h2-icon {
  color: var(--brand-500);
  font-size: 15px;
}

.multi-shop-hint {
  margin-bottom: 11px;
}

.panel-head p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 11.5px;
}

.panel-badge {
  flex: none;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: 11px;
  font-weight: 600;
}

.shop-name-link {
  color: var(--brand-600);
  font-weight: 600;
}

.panel-alert {
  margin-bottom: 12px;
}

.address-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.address-option {
  position: relative;
  padding: 13px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface-soft);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    background-color 0.16s ease,
    box-shadow 0.16s ease;
}

.address-option:hover {
  border-color: var(--brand-200);
}

.address-option.active {
  border-color: var(--brand-400);
  background: #f4f9ff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.08);
}

.address-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding-right: 20px;
}

.address-head b {
  color: var(--ink);
  font-size: 13.5px;
  font-weight: 750;
}

.address-head span {
  color: var(--muted);
  font-size: 11.5px;
}

.address-option p {
  margin: 5px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.address-check {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--brand-500);
  color: #fff;
  font-size: 11px;
}

.goods-row {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 12px;
  border: 1px solid #eef5ff;
  border-radius: 13px;
  background: var(--surface-soft);
}

.goods-row + .goods-row {
  margin-top: 8px;
}

.goods-thumb {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 82px;
  height: 82px;
  flex: none;
  overflow: hidden;
  border-radius: 10px;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
}

.goods-thumb img {
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-initial {
  position: absolute;
  color: var(--brand-400);
  font-size: 28px;
  font-weight: 850;
}

.goods-info {
  flex: 1;
  min-width: 0;
}

.goods-info h3 {
  margin: 0;
  overflow: hidden;
  font-size: 14.5px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-info p {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-tags {
  display: flex;
  gap: 10px;
  margin-top: 6px;
  color: var(--faint);
  font-size: 11px;
}

.goods-buy {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  flex: none;
}

.goods-price {
  color: var(--brand-600);
  font-size: 18px;
  font-weight: 850;
  line-height: 1;
}

.voucher-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.voucher-option {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 13px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface-soft);
  color: var(--ink-2);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    background-color 0.16s ease;
}

.voucher-option:hover {
  border-color: var(--brand-200);
}

.voucher-option.active {
  border-color: var(--brand-400);
  background: #f4f9ff;
}

.voucher-option.none {
  justify-content: space-between;
  color: var(--muted);
  font-size: 12.5px;
}

.vo-value {
  width: 76px;
  flex: none;
  color: var(--brand-600);
  font-size: 17px;
  font-weight: 850;
  letter-spacing: -0.03em;
}

.vo-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.vo-body b {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vo-body small {
  color: var(--faint);
  font-size: 10.5px;
}

.voucher-option > .el-icon {
  flex: none;
  color: var(--brand-500);
}

.voucher-none {
  padding: 14px;
  border: 1px dashed var(--line-strong);
  border-radius: 11px;
  color: var(--faint);
  font-size: 12px;
  text-align: center;
}

.summary-card {
  position: sticky;
  top: 88px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--muted);
  font-size: 12.5px;
}

.summary-total {
  margin-top: 5px;
  color: var(--brand-600);
  font-size: 32px;
  font-weight: 850;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.summary-hint {
  margin: 3px 0 0;
  color: var(--faint);
  font-size: 10.5px;
}

.summary-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #f0f6ff;
}

.summary-rows div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--muted);
  font-size: 12px;
}

.summary-rows b {
  color: var(--ink-2);
  font-weight: 650;
}

.summary-rows .discount {
  color: #059669;
}

.balance-alert {
  margin-top: 13px;
}

.submit-order {
  width: 100%;
  height: 44px;
  margin-top: 16px;
}

.summary-note {
  margin: 10px 0 0;
  color: var(--faint);
  font-size: 10.5px;
  line-height: 1.6;
}

.pay-layout {
  display: grid;
  grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
  align-items: start;
  gap: 22px;
}

.pay-card {
  padding: 28px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.pay-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pay-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: 18px;
}

.pay-head > div {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.pay-head b {
  font-size: 14.5px;
  font-weight: 750;
}

.pay-head span {
  overflow: hidden;
  color: var(--faint);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pay-amount {
  margin-top: 26px;
  color: var(--brand-700);
  font-size: 46px;
  font-weight: 850;
  line-height: 1;
  letter-spacing: -0.05em;
}

.pay-amount-label {
  margin: 5px 0 0;
  color: var(--faint);
  font-size: 11.5px;
}

.pay-balance {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20px 0 0;
  padding: 12px 14px;
  border-radius: 11px;
  background: var(--brand-50);
  color: var(--muted);
  font-size: 12px;
}

.pay-balance b {
  color: var(--brand-700);
  font-size: 14px;
  font-weight: 800;
}

.pay-alert {
  margin-top: 12px;
}

.pay-label {
  display: block;
  margin: 20px 0 7px;
  color: var(--ink-2);
  font-size: 12.5px;
  font-weight: 600;
}

.pay-demo-tip {
  margin: 0 0 8px;
  color: var(--faint);
  font-size: 10.5px;
}

.pay-demo-tip code {
  padding: 1px 5px;
  border-radius: 5px;
  background: var(--brand-50);
  color: var(--brand-600);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-weight: 700;
}

.pay-submit {
  width: 100%;
  height: 46px;
  margin-top: 16px;
  gap: 5px;
}

.pay-later {
  display: flex;
  margin: 10px auto 0;
}

.pay-footnote {
  margin: 16px 0 0;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
  color: var(--faint);
  font-size: 10.5px;
  line-height: 1.6;
  text-align: center;
}

.pay-summary {
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.pay-summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 15px;
}

.pay-summary-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 750;
}

.pay-summary-head a {
  overflow: hidden;
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pay-goods {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 12px;
  background: var(--surface-soft);
  border: 1px solid #eef5ff;
}

.pay-goods + .pay-goods {
  margin-top: 8px;
}

.pay-goods-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  flex: none;
  overflow: hidden;
  border-radius: 9px;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
  color: var(--brand-500);
  font-size: 20px;
  font-weight: 800;
}

.pay-goods-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.pay-goods-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.pay-goods-info b {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pay-goods-info span {
  color: var(--faint);
  font-size: 10.5px;
}

.pay-goods-price {
  flex: none;
  color: var(--brand-600);
  font-size: 13.5px;
  font-weight: 800;
}

.pay-summary-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 15px;
  padding-top: 13px;
  border-top: 1px solid #f0f6ff;
}

.pay-summary-rows div {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  color: var(--muted);
  font-size: 11.5px;
}

.pay-summary-rows b {
  max-width: 68%;
  color: var(--ink-2);
  font-weight: 600;
  text-align: right;
}

.address-text {
  line-height: 1.5;
}

.pay-summary-total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  padding-top: 13px;
  border-top: 1px dashed var(--line-strong);
  color: var(--ink-2);
  font-size: 12.5px;
}

.pay-summary-total b {
  color: var(--brand-600);
  font-size: 22px;
  font-weight: 850;
  letter-spacing: -0.03em;
}

.success-wrap {
  padding: 30px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.address-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 14px;
}

.address-form-grid .full {
  grid-column: 1 / -1;
}

@media (max-width: 1024px) {
  .checkout-layout,
  .pay-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .summary-card {
    position: static;
  }

  .pay-summary {
    order: -1;
  }
}

@media (max-width: 640px) {
  .checkout-top {
    align-items: flex-start;
  }

  .checkout-steps {
    gap: 5px;
  }

  .checkout-step > span:last-child {
    display: none;
  }

  .checkout-steps i {
    width: 14px;
  }

  .checkout-panel {
    padding: 15px;
  }

  .address-list {
    grid-template-columns: minmax(0, 1fr);
  }

  .goods-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .goods-info {
    flex-basis: calc(100% - 100px);
  }

  .goods-buy {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    padding-top: 8px;
    border-top: 1px dashed var(--line);
  }

  .pay-card,
  .pay-summary {
    padding: 19px;
    border-radius: var(--radius);
  }

  .pay-amount {
    font-size: 38px;
  }

  .address-form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .address-form-grid .full {
    grid-column: 1 / -1;
  }
}
</style>
