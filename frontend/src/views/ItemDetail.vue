<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  Goods,
  ShoppingBag,
  ShoppingCart,
  Van,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import { cartApi, itemApi, shopApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import type { Item, Shop } from '@/api/types'
import { formatCount, formatPrice, initialOf } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const itemId = computed(() => String(route.params.id || ''))
const item = ref<Item | null>(null)
const shop = ref<Shop | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const qty = ref(1)
const adding = ref(false)
const imageBroken = ref(false)

const maxQty = computed(() => Math.max(1, Number(item.value?.stock || 1)))
const soldOut = computed(() => Number(item.value?.stock || 0) <= 0)
const specText = computed(() => {
  const raw = item.value?.spec
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const entries = Object.entries(parsed)
      .filter(([, value]) => value !== null && value !== undefined && String(value) !== '')
      .map(([key, value]) => `${key}：${String(value)}`)
    return entries.join(' / ')
  } catch {
    return raw
  }
})

async function loadDetail(): Promise<void> {
  if (!itemId.value) {
    loading.value = false
    errorMessage.value = '缺少商品 id'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const detail = await itemApi.byId(itemId.value)
    item.value = detail
    qty.value = 1
    if (detail.shopId) {
      try {
        shop.value = await shopApi.detail(detail.shopId)
      } catch {
        shop.value = null
      }
    }
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '商品详情加载失败')
  } finally {
    loading.value = false
  }
}

async function addToCart(): Promise<void> {
  if (!item.value || soldOut.value || adding.value) return
  adding.value = true
  try {
    await cartApi.add({
      itemId: item.value.id,
      name: item.value.name,
      spec: item.value.spec,
      price: Number(item.value.price),
      image: item.value.image,
    })
    ElMessage.success('已加入购物车')
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '加入购物车失败'))
  } finally {
    adding.value = false
  }
}

function buyNow(): void {
  if (!item.value || soldOut.value) return
  void router.push({
    name: 'checkout',
    query: {
      itemId: item.value.id,
      num: String(qty.value),
      ...(item.value.shopId ? { shopId: item.value.shopId } : {}),
    },
  })
}

function goShop(): void {
  if (!item.value?.shopId) return
  void router.push({ name: 'shop-detail', params: { id: item.value.shopId } })
}

onMounted(() => {
  void loadDetail()
})
</script>

<template>
  <StateBlock
    :loading="loading"
    :error="errorMessage"
    :empty="!loading && !errorMessage && !item"
    empty-text="商品不存在或已下架"
    @retry="loadDetail"
  >
    <div v-if="item" class="item-page">
      <div class="item-top">
        <el-button text :icon="ArrowLeft" @click="router.back()">返回</el-button>
        <span class="crumb">商城 / {{ item.name }}</span>
      </div>

      <PageHeader eyebrow="PRODUCT DETAIL" title="商品详情" :subtitle="item.category || '精选好物'" />

      <section class="item-hero">
        <div class="hero-cover">
          <img
            v-if="item.image && !imageBroken"
            :src="item.image"
            :alt="item.name"
            @error="imageBroken = true"
          />
          <div v-else class="cover-fallback">{{ initialOf(item.name) }}</div>
          <span v-if="soldOut" class="cover-soldout">已售罄</span>
        </div>

        <div class="hero-body">
          <div class="hero-badges">
            <span class="badge category">{{ item.category || '精选好物' }}</span>
            <span v-if="item.type === 2" class="badge o2o">到店核销</span>
            <span v-else class="badge express">实物配送</span>
          </div>

          <h1>{{ item.name }}</h1>

          <div class="price-row">
            <span class="price">{{ formatPrice(item.price) }}</span>
            <span class="price-hint">/ {{ item.type === 2 ? '份' : '件' }}</span>
          </div>

          <div class="meta-grid">
            <div>
              <span>销量</span>
              <b>{{ formatCount(item.sold) }}</b>
            </div>
            <div>
              <span>库存</span>
              <b :class="{ danger: soldOut }">{{ item.stock }}</b>
            </div>
            <div>
              <span>评价</span>
              <b>{{ formatCount(item.commentCount) }}</b>
            </div>
            <div v-if="item.brand">
              <span>品牌</span>
              <b>{{ item.brand }}</b>
            </div>
          </div>

          <div v-if="specText" class="spec-line">
            <span>规格</span>
            <p>{{ specText }}</p>
          </div>

          <div class="shop-line">
            <span class="shop-icon"><el-icon><Goods /></el-icon></span>
            <div v-if="shop" class="shop-info">
              <span>所属门店</span>
              <router-link :to="{ name: 'shop-detail', params: { id: shop.id } }">
                {{ shop.name }}
                <small>{{ shop.area || shop.address }}</small>
              </router-link>
            </div>
            <div v-else-if="item.shopId" class="shop-info">
              <span>所属门店</span>
              <a class="shop-link" @click="goShop">门店 {{ item.shopId }}</a>
            </div>
            <div v-else class="shop-info">
              <span>所属门店</span>
              <b>LLMall 自营</b>
            </div>
            <el-button v-if="item.shopId" size="small" @click="goShop">进店逛逛</el-button>
          </div>

          <div class="buy-row">
            <div class="qty-control">
              <span>数量</span>
              <el-input-number
                v-model="qty"
                :min="1"
                :max="maxQty"
                :disabled="soldOut"
                size="large"
              />
            </div>
            <el-button
              type="primary"
              size="large"
              :icon="ShoppingCart"
              :loading="adding"
              :disabled="soldOut"
              @click="addToCart"
            >
              加入购物车
            </el-button>
            <el-button size="large" :icon="ShoppingBag" :disabled="soldOut" @click="buyNow">
              立即购买
            </el-button>
          </div>

          <p class="buy-hint">
            <el-icon><Van /></el-icon>
            {{ item.type === 2 ? '下单后到店出示核销码使用，未使用可随时退。' : '实物商品下单后按收货地址配送。' }}
          </p>
        </div>
      </section>
    </div>
  </StateBlock>
</template>

<style scoped>
.item-page {
  width: 100%;
}

.item-top {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  color: var(--faint);
  font-size: 12px;
}

.item-top :deep(.el-button) {
  color: var(--muted);
}

.crumb {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-hero {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.hero-cover {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 420px;
  overflow: hidden;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
}

.hero-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-fallback {
  color: var(--brand-500);
  font-size: 88px;
  font-weight: 850;
}

.cover-soldout {
  position: absolute;
  top: 16px;
  left: 16px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.66);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.hero-body {
  display: flex;
  flex-direction: column;
  padding: 30px;
}

.hero-badges {
  display: flex;
  gap: 7px;
}

.badge {
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.badge.category {
  background: var(--brand-50);
  color: var(--brand-600);
}

.badge.o2o {
  background: #ecfdf5;
  color: #059669;
}

.badge.express {
  background: #fff7ed;
  color: #c2410c;
}

.hero-body h1 {
  margin: 13px 0 0;
  color: var(--ink);
  font-size: clamp(23px, 2.2vw, 32px);
  font-weight: 850;
  line-height: 1.3;
  letter-spacing: -0.03em;
}

.price-row {
  display: flex;
  align-items: baseline;
  gap: 5px;
  margin-top: 15px;
  padding: 14px 16px;
  border-radius: 13px;
  background: linear-gradient(135deg, #eff6ff, #f8fbff);
}

.price {
  color: var(--brand-600);
  font-size: 36px;
  font-weight: 850;
  line-height: 1;
  letter-spacing: -0.04em;
}

.price-hint {
  color: var(--faint);
  font-size: 12px;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-top: 16px;
}

.meta-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-soft);
}

.meta-grid span {
  color: var(--faint);
  font-size: 10.5px;
}

.meta-grid b {
  overflow: hidden;
  color: var(--ink-2);
  font-size: 13.5px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta-grid b.danger {
  color: #dc2626;
}

.spec-line {
  display: flex;
  gap: 10px;
  margin-top: 14px;
  color: var(--muted);
  font-size: 12.5px;
}

.spec-line > span {
  flex: none;
  color: var(--faint);
}

.spec-line p {
  margin: 0;
  line-height: 1.6;
}

.shop-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid #eef5ff;
  border-radius: 12px;
  background: #fbfdff;
}

.shop-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 10px;
  background: var(--brand-50);
  color: var(--brand-500);
}

.shop-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.shop-info > span {
  color: var(--faint);
  font-size: 10.5px;
}

.shop-info a,
.shop-info b {
  overflow: hidden;
  color: var(--ink-2);
  font-size: 13.5px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.shop-info a small {
  margin-left: 6px;
  color: var(--faint);
  font-size: 10.5px;
  font-weight: 500;
}

.buy-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 22px;
}

.qty-control {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-right: 4px;
  color: var(--muted);
  font-size: 12px;
}

.qty-control :deep(.el-input-number) {
  width: 116px;
}

.buy-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 16px 0 0;
  color: var(--faint);
  font-size: 11px;
}

@media (max-width: 1024px) {
  .item-hero {
    grid-template-columns: minmax(0, 1fr);
  }

  .hero-cover {
    min-height: 300px;
  }

  .hero-body {
    padding: 24px;
  }
}

@media (max-width: 640px) {
  .hero-cover {
    min-height: 230px;
  }

  .hero-body {
    padding: 17px;
  }

  .meta-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .buy-row :deep(.el-button) {
    flex: 1;
    margin-left: 0;
  }

  .qty-control {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
