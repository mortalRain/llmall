<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  Clock,
  Location,
  Refresh,
  ShoppingBag,
  StarFilled,
} from '@element-plus/icons-vue'
import StateBlock from '@/components/StateBlock.vue'
import VoucherCard from '@/components/VoucherCard.vue'
import { itemApi, shopApi, voucherApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import type { Item, Shop, Voucher } from '@/api/types'
import {
  formatCount,
  formatPrice,
  initialOf,
  scoreOf,
  splitImages,
} from '@/utils/format'

const route = useRoute()
const router = useRouter()

const shopId = computed(() => String(route.params.id || ''))
const shop = ref<Shop | null>(null)
const items = ref<Item[]>([])
const vouchers = ref<Voucher[]>([])
const loading = ref(true)
const errorMessage = ref('')
const itemsError = ref('')
const vouchersError = ref('')
const quantities = reactive<Record<string, number>>({})
const receivingId = ref('')
const itemsSection = ref<HTMLElement | null>(null)
const brokenImages = reactive<Record<number, boolean>>({})

const images = computed(() => splitImages(shop.value?.images).slice(0, 3))
const mainImage = computed(() => images.value[0] || '')
const sideImages = computed(() => images.value.slice(1, 3))
const shopScore = computed(() => scoreOf(shop.value?.score))
const stars = computed(() => Math.round(Number(shop.value?.score || 0) / 10))
const statusText = computed(() => {
  switch (Number(shop.value?.status)) {
    case 1:
      return '营业中'
    case 2:
      return '休息中'
    case 3:
      return '已下线'
    default:
      return '营业状态未知'
  }
})

async function loadDetail(): Promise<void> {
  if (!shopId.value) {
    errorMessage.value = '缺少门店 id'
    loading.value = false
    return
  }
  loading.value = true
  errorMessage.value = ''
  itemsError.value = ''
  vouchersError.value = ''
  try {
    shop.value = await shopApi.detail(shopId.value)
    const [itemResult, voucherResult] = await Promise.allSettled([
      itemApi.byShop(shopId.value),
      voucherApi.list(shopId.value),
    ])

    if (itemResult.status === 'fulfilled') {
      items.value = Array.isArray(itemResult.value) ? itemResult.value : []
      items.value.forEach((item) => {
        if (!quantities[item.id]) quantities[item.id] = 1
      })
    } else {
      itemsError.value = getErrorMessage(itemResult.reason, '商品加载失败')
    }

    if (voucherResult.status === 'fulfilled') {
      vouchers.value = Array.isArray(voucherResult.value) ? voucherResult.value : []
    } else {
      vouchersError.value = getErrorMessage(voucherResult.reason, '优惠券加载失败')
    }
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '门店详情加载失败')
  } finally {
    loading.value = false
  }
}

async function retryItems(): Promise<void> {
  itemsError.value = ''
  try {
    items.value = await itemApi.byShop(shopId.value)
    items.value.forEach((item) => {
      if (!quantities[item.id]) quantities[item.id] = 1
    })
  } catch (error) {
    itemsError.value = getErrorMessage(error, '商品加载失败')
  }
}

async function retryVouchers(): Promise<void> {
  vouchersError.value = ''
  try {
    vouchers.value = await voucherApi.list(shopId.value)
  } catch (error) {
    vouchersError.value = getErrorMessage(error, '优惠券加载失败')
  }
}

async function refreshVouchers(): Promise<void> {
  try {
    vouchers.value = await voucherApi.list(shopId.value)
  } catch (error) {
    vouchersError.value = getErrorMessage(error, '优惠券刷新失败')
  }
}

function scrollToItems(): void {
  itemsSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function buy(item: Item): void {
  if (Number(item.stock) <= 0) return
  const num = Math.min(
    Math.max(1, Number(quantities[item.id] || 1)),
    Math.max(1, Number(item.stock)),
  )
  void router.push({
    name: 'checkout',
    query: {
      itemId: item.id,
      num: String(num),
      shopId: shopId.value,
    },
  })
}

async function receive(voucher: Voucher): Promise<void> {
  if (voucher.received) return

  let pw: string | undefined
  if (Number(voucher.payValue) > 0) {
    try {
      const result = await ElMessageBox.prompt(
        `该券为付费券，需支付 ${formatPrice(voucher.payValue)}。请输入支付口令（演示支付口令：888888，与登录密码不同）。`,
        '购买优惠券',
        {
          confirmButtonText: '确认购买',
          cancelButtonText: '取消',
          inputType: 'password',
          inputPlaceholder: '请输入支付口令',
          inputValidator: (value: string) =>
            Boolean(value && value.trim().length >= 6) || '请输入至少 6 位支付口令',
        },
      )
      pw = result.value
    } catch {
      return
    }
  }

  receivingId.value = voucher.id
  try {
    await voucherApi.receive(voucher.id, pw)
    ElMessage.success('领取成功，可在「我的」中查看优惠券')
    await refreshVouchers()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '领取失败'))
  } finally {
    receivingId.value = ''
  }
}

function goOrders(): void {
  void router.push('/orders')
}

onMounted(() => {
  void loadDetail()
})
</script>

<template>
  <StateBlock
    :loading="loading"
    :error="errorMessage"
    :empty="!loading && !errorMessage && !shop"
    empty-text="门店不存在或已下线"
    @retry="loadDetail"
  >
    <div v-if="shop" class="shop-detail">
      <div class="detail-top">
        <el-button text :icon="ArrowLeft" @click="router.back()">返回</el-button>
        <span class="crumb">门店 / {{ shop.name }}</span>
      </div>

      <section class="shop-hero">
        <div class="gallery" :class="{ single: !sideImages.length }">
          <div class="gallery-main">
            <img
              v-if="mainImage && !brokenImages[0]"
              :src="mainImage"
              :alt="shop.name"
              @error="brokenImages[0] = true"
            />
            <div v-else class="gallery-fallback">
              <span>{{ initialOf(shop.name) }}</span>
              <small>LLMall 精选门店</small>
            </div>
            <span class="gallery-type">{{ shop.typeName || '精选门店' }}</span>
          </div>
          <div v-for="(image, index) in sideImages" :key="image" class="gallery-side">
            <img
              v-if="!brokenImages[index + 1]"
              :src="image"
              :alt="`${shop.name} 环境图 ${index + 1}`"
              @error="brokenImages[index + 1] = true"
            />
            <div v-else class="gallery-side-fallback"></div>
          </div>
        </div>

        <div class="hero-info">
          <div class="hero-tags">
            <span class="tag-type">{{ shop.typeName || '同城门店' }}</span>
            <span class="tag-status" :class="{ closed: Number(shop.status) !== 1 }">
              {{ statusText }}
            </span>
          </div>

          <h1>{{ shop.name }}</h1>

          <div class="hero-rating">
            <span class="score">{{ shopScore }}</span>
            <span class="stars">
              <el-icon v-for="index in 5" :key="index" :class="{ on: index <= stars }">
                <StarFilled />
              </el-icon>
            </span>
            <span class="rating-text">{{ formatCount(shop.comments) }} 条评价</span>
            <span class="rating-text">已售 {{ formatCount(shop.sold) }}</span>
          </div>

          <div class="hero-meta">
            <div>
              <el-icon><Location /></el-icon>
              <span>{{ shop.area ? `${shop.area} · ` : '' }}{{ shop.address || '地址待完善' }}</span>
            </div>
            <div>
              <el-icon><Clock /></el-icon>
              <span>营业时间 {{ shop.openHours || '以门店公告为准' }}</span>
            </div>
          </div>

          <div class="hero-price">
            <span>人均 <b>{{ formatPrice(shop.avgPrice) }}</b></span>
            <span>热度 <b>{{ Number(shop.hotScore || 0).toFixed(1) }}</b></span>
          </div>

          <div class="hero-actions">
            <el-button type="primary" size="large" :icon="ShoppingBag" @click="scrollToItems">
              开始点单
            </el-button>
            <el-button size="large" @click="goOrders">我的订单</el-button>
          </div>
        </div>
      </section>

      <section ref="itemsSection" class="section-block">
        <div class="section-head">
          <div>
            <span class="eyebrow">ORDER ONLINE</span>
            <h2>商品套餐</h2>
            <p>选择数量后立即购买，结算时使用余额支付，支付成功即生成核销码。</p>
          </div>
          <span class="section-count">{{ items.length }} 项</span>
        </div>

        <div v-if="itemsError" class="inline-error">
          <span>{{ itemsError }}</span>
          <el-button size="small" :icon="Refresh" @click="retryItems">重试</el-button>
        </div>
        <el-empty v-else-if="!items.length" description="该门店暂未上架商品" />
        <div v-else class="item-list">
          <article v-for="item in items" :key="item.id" class="item-row">
            <div class="item-thumb">
              <img
                v-if="item.image"
                :src="item.image"
                :alt="item.name"
                loading="lazy"
                @error="($event.target as HTMLImageElement).style.display = 'none'"
              />
              <span class="thumb-initial">{{ initialOf(item.name) }}</span>
            </div>

            <div class="item-info">
              <h3>{{ item.name }}</h3>
              <p>{{ item.spec || item.category || '到店核销 · 过期自动退' }}</p>
              <div class="item-tags">
                <span>已售 {{ formatCount(item.sold) }}</span>
                <span>库存 {{ item.stock }}</span>
                <span v-if="item.brand">{{ item.brand }}</span>
              </div>
            </div>

            <div class="item-buy">
              <div class="item-price">
                {{ formatPrice(item.price) }}
                <small>/份</small>
              </div>
              <div class="item-controls">
                <el-input-number
                  v-model="quantities[item.id]"
                  :min="1"
                  :max="Math.max(1, Number(item.stock))"
                  :disabled="Number(item.stock) <= 0"
                  size="small"
                />
                <el-button
                  type="primary"
                  :disabled="Number(item.stock) <= 0"
                  @click="buy(item)"
                >
                  {{ Number(item.stock) > 0 ? '立即购买' : '已售罄' }}
                </el-button>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="section-block">
        <div class="section-head">
          <div>
            <span class="eyebrow">COUPONS</span>
            <h2>门店优惠券</h2>
            <p>先领券再下单，结算时选择使用可抵扣订单金额。</p>
          </div>
          <span class="section-count">{{ vouchers.length }} 张</span>
        </div>

        <div v-if="vouchersError" class="inline-error">
          <span>{{ vouchersError }}</span>
          <el-button size="small" :icon="Refresh" @click="retryVouchers">重试</el-button>
        </div>
        <el-empty v-else-if="!vouchers.length" description="该门店暂无可领取的优惠券" />
        <div v-else class="voucher-list">
          <VoucherCard
            v-for="voucher in vouchers"
            :key="voucher.id"
            :voucher="voucher"
            mode="shop"
            :loading="receivingId === voucher.id"
            @receive="receive(voucher)"
          />
        </div>
      </section>
    </div>
  </StateBlock>
</template>

<style scoped>
.shop-detail {
  width: 100%;
}

.detail-top {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  color: var(--faint);
  font-size: 12px;
}

.detail-top :deep(.el-button) {
  color: var(--muted);
}

.crumb {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.03fr) minmax(0, 0.97fr);
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
}

.gallery {
  display: grid;
  grid-template-columns: 1.65fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 3px;
  min-height: 380px;
  background: #dce9fb;
}

.gallery.single {
  grid-template-columns: 1fr;
  grid-template-rows: 1fr;
}

.gallery-main {
  position: relative;
  grid-row: 1 / span 2;
  overflow: hidden;
}

.gallery.single .gallery-main {
  grid-row: 1;
}

.gallery-main img,
.gallery-side img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.gallery-side {
  overflow: hidden;
  background: #e8f1ff;
}

.gallery-fallback {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background:
    radial-gradient(circle at 25% 20%, rgba(255, 255, 255, 0.72), transparent 45%),
    linear-gradient(140deg, #bfdbfe 0%, #3b82f6 100%);
}

.gallery-fallback span {
  color: rgba(255, 255, 255, 0.94);
  font-size: 78px;
  font-weight: 850;
  line-height: 1;
}

.gallery-fallback small {
  margin-top: 6px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 11px;
  letter-spacing: 0.16em;
}

.gallery-side-fallback {
  width: 100%;
  height: 100%;
  background: linear-gradient(160deg, #dbeafe, #eff6ff);
}

.gallery-type {
  position: absolute;
  top: 14px;
  left: 14px;
  padding: 5px 11px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.48);
  color: #fff;
  font-size: 11.5px;
  font-weight: 700;
  backdrop-filter: blur(8px);
}

.hero-info {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 30px 30px 28px;
}

.hero-tags {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tag-type,
.tag-status {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 700;
}

.tag-type {
  background: var(--brand-50);
  color: var(--brand-600);
}

.tag-status {
  background: #ecfdf5;
  color: #059669;
}

.tag-status.closed {
  background: #f8fafc;
  color: var(--muted);
}

.hero-info h1 {
  margin: 13px 0 0;
  color: var(--ink);
  font-size: clamp(26px, 2.4vw, 36px);
  font-weight: 850;
  line-height: 1.22;
  letter-spacing: -0.04em;
}

.hero-rating {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.hero-rating .score {
  color: var(--gold);
  font-size: 20px;
  font-weight: 850;
  line-height: 1;
}

.stars {
  display: inline-flex;
  gap: 1px;
  color: #dbe7f8;
  font-size: 14px;
}

.stars .on {
  color: #f3b33d;
}

.rating-text {
  color: var(--muted);
  font-size: 12px;
}

.hero-meta {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #f0f6ff;
}

.hero-meta div {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  color: var(--ink-2);
  font-size: 12.5px;
  line-height: 1.6;
}

.hero-meta .el-icon {
  margin-top: 3px;
  flex: none;
  color: var(--brand-400);
}

.hero-price {
  display: flex;
  gap: 22px;
  margin-top: 16px;
  color: var(--muted);
  font-size: 12.5px;
}

.hero-price b {
  color: var(--ink);
  font-size: 15px;
  font-weight: 800;
}

.hero-actions {
  display: flex;
  gap: 10px;
  margin-top: 22px;
}

.section-block {
  margin-top: 24px;
  padding: 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.section-head h2 {
  margin: 3px 0 0;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.025em;
}

.section-head p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 12.5px;
}

.section-count {
  flex: none;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: 11.5px;
  font-weight: 700;
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.item-row {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 12px;
  border: 1px solid #eef5ff;
  border-radius: 14px;
  transition:
    border-color 0.16s ease,
    background-color 0.16s ease;
}

.item-row:hover {
  border-color: var(--brand-200);
  background: #fbfdff;
}

.item-thumb {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 90px;
  height: 90px;
  overflow: hidden;
  border-radius: 11px;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
}

.item-thumb img {
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-initial {
  position: absolute;
  color: var(--brand-400);
  font-size: 30px;
  font-weight: 850;
}

.item-info {
  min-width: 0;
}

.item-info h3 {
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  font-size: 15px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-info p {
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 7px;
  color: var(--faint);
  font-size: 11px;
}

.item-buy {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.item-price {
  color: var(--brand-600);
  font-size: 21px;
  font-weight: 850;
  line-height: 1;
  letter-spacing: -0.03em;
  white-space: nowrap;
}

.item-price small {
  color: var(--faint);
  font-size: 10.5px;
  font-weight: 500;
}

.item-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.item-controls :deep(.el-input-number) {
  width: 92px;
}

.voucher-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.inline-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #fde2e2;
  border-radius: 12px;
  background: #fff7f7;
  color: #b91c1c;
  font-size: 12.5px;
}

@media (max-width: 1024px) {
  .shop-hero {
    grid-template-columns: minmax(0, 1fr);
  }

  .gallery {
    min-height: 280px;
  }

  .hero-info {
    padding: 24px;
  }

  .voucher-list {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 640px) {
  .gallery {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: 210px;
    min-height: 0;
  }

  .gallery-main {
    grid-row: auto;
  }

  .gallery-side {
    display: none;
  }

  .hero-info {
    padding: 18px 16px 20px;
  }

  .hero-info h1 {
    font-size: 23px;
  }

  .hero-actions {
    flex-wrap: wrap;
  }

  .hero-actions :deep(.el-button) {
    flex: 1;
    margin-left: 0;
  }

  .section-block {
    margin-top: 16px;
    padding: 16px;
    border-radius: var(--radius);
  }

  .section-head h2 {
    font-size: 19px;
  }

  .section-head p {
    display: none;
  }

  .item-row {
    grid-template-columns: 70px minmax(0, 1fr);
    gap: 10px;
    padding: 10px;
  }

  .item-thumb {
    width: 70px;
    height: 70px;
  }

  .item-buy {
    grid-column: 1 / -1;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    margin-top: 2px;
  }

  .item-controls :deep(.el-input-number) {
    width: 100px;
  }
}
</style>
