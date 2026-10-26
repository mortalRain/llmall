<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Goods, Refresh, Search, ShoppingCart } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import { cartApi, itemApi, shopApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import type { Item, ItemCategory } from '@/api/types'
import { formatCount, formatPrice, initialOf } from '@/utils/format'

const PAGE_SIZE = 12

const router = useRouter()

const items = ref<Item[]>([])
const categories = ref<ItemCategory[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(true)
const errorMessage = ref('')

const keyword = ref('')
const appliedKeyword = ref('')
const category = ref('')
/** 价格区间按"元"输入，请求前换算成分；空串表示不限 */
const minPriceInput = ref('')
const maxPriceInput = ref('')

const shopNames = reactive<Record<string, string>>({})
const brokenImages = reactive<Record<string, boolean>>({})
const addingId = ref('')

const hasFilters = computed(
  () =>
    Boolean(appliedKeyword.value) ||
    Boolean(category.value) ||
    Boolean(minPriceInput.value.trim()) ||
    Boolean(maxPriceInput.value.trim()),
)

function toFen(value: string): number | undefined {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const yuan = Number(trimmed)
  if (!Number.isFinite(yuan) || yuan < 0) return undefined
  return Math.round(yuan * 100)
}

function shopLabel(item: Item): string {
  if (!item.shopId) return 'LLMall 自营'
  return shopNames[String(item.shopId)] || `门店 ${item.shopId}`
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await itemApi.categories()
  } catch {
    // 分类是筛选项，加载失败不阻断商品列表；页面仍可按关键词/价格搜索
    categories.value = []
  }
}

async function loadShops(rows: Item[]): Promise<void> {
  const ids = Array.from(
    new Set(rows.map((item) => item.shopId).filter((id): id is string => Boolean(id))),
  )
  if (!ids.length) return
  try {
    const shops = await shopApi.batch(ids)
    shops.forEach((shop) => {
      shopNames[String(shop.id)] = shop.name
    })
  } catch {
    // 店铺名只是展示增强，失败时退回"门店 {id}"，不影响加购/进详情
  }
}

async function loadItems(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await itemApi.page({
      pageNo: page.value,
      pageSize: PAGE_SIZE,
      ...(appliedKeyword.value ? { key: appliedKeyword.value } : {}),
      ...(category.value ? { category: category.value } : {}),
      ...(toFen(minPriceInput.value) !== undefined
        ? { minPrice: toFen(minPriceInput.value) }
        : {}),
      ...(toFen(maxPriceInput.value) !== undefined
        ? { maxPrice: toFen(maxPriceInput.value) }
        : {}),
    })
    items.value = Array.isArray(result.list) ? result.list : []
    total.value = Number(result.total || 0)
    await loadShops(items.value)
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '商品加载失败')
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function applyFilters(): void {
  if (toFen(minPriceInput.value) !== undefined && toFen(maxPriceInput.value) !== undefined
    && Number(minPriceInput.value) > Number(maxPriceInput.value)) {
    ElMessage.warning('最低价不能高于最高价')
    return
  }
  appliedKeyword.value = keyword.value.trim()
  page.value = 1
  void loadItems()
}

function clearFilters(): void {
  keyword.value = ''
  appliedKeyword.value = ''
  category.value = ''
  minPriceInput.value = ''
  maxPriceInput.value = ''
  page.value = 1
  void loadItems()
}

function changePage(nextPage: number): void {
  page.value = nextPage
  void loadItems()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function goDetail(item: Item): void {
  void router.push({ name: 'item-detail', params: { id: item.id } })
}

async function addToCart(item: Item): Promise<void> {
  if (Number(item.stock) <= 0 || addingId.value) return
  addingId.value = item.id
  try {
    await cartApi.add({
      itemId: item.id,
      name: item.name,
      spec: item.spec,
      price: Number(item.price),
      image: item.image,
    })
    ElMessage.success(`已把「${item.name}」加入购物车`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '加入购物车失败'))
  } finally {
    addingId.value = ''
  }
}

function goCart(): void {
  void router.push('/cart')
}

onMounted(() => {
  void loadCategories()
  void loadItems()
})
</script>

<template>
  <div class="mall-page">
    <PageHeader
      eyebrow="LOCAL LIFE MALL"
      title="商城"
      subtitle="搜套餐、挑好物，加入购物车后一次结算；门店套餐到店核销，实物商品配送到家。"
    >
      <template #actions>
        <el-button :icon="ShoppingCart" @click="goCart">购物车</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadItems">刷新商品</el-button>
      </template>
    </PageHeader>

    <div class="mall-toolbar">
      <el-input
        v-model="keyword"
        size="large"
        clearable
        :prefix-icon="Search"
        placeholder="搜索商品名称，例如：双人套餐"
        class="keyword-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      />
      <el-select
        v-model="category"
        size="large"
        clearable
        placeholder="全部分类"
        class="category-select"
        @change="applyFilters"
      >
        <el-option
          v-for="entry in categories"
          :key="entry.category"
          :label="`${entry.category}（${entry.count}）`"
          :value="entry.category"
        />
      </el-select>
      <div class="price-range">
        <el-input
          v-model="minPriceInput"
          size="large"
          inputmode="decimal"
          placeholder="最低价"
          class="price-input"
          @keyup.enter="applyFilters"
        >
          <template #suffix>元</template>
        </el-input>
        <span class="price-sep">—</span>
        <el-input
          v-model="maxPriceInput"
          size="large"
          inputmode="decimal"
          placeholder="最高价"
          class="price-input"
          @keyup.enter="applyFilters"
        >
          <template #suffix>元</template>
        </el-input>
      </div>
      <el-button type="primary" size="large" :icon="Search" @click="applyFilters">筛选</el-button>
      <el-button v-if="hasFilters" size="large" @click="clearFilters">清空</el-button>
      <span class="toolbar-count">
        共 <b>{{ total }}</b> 件商品
        <i v-if="appliedKeyword">· “{{ appliedKeyword }}”</i>
      </span>
    </div>

    <div v-if="loading" class="mall-grid">
      <div v-for="index in 8" :key="index" class="skeleton-card">
        <el-skeleton-item variant="image" class="skeleton-cover" />
        <div class="skeleton-body">
          <el-skeleton :rows="2" animated />
        </div>
      </div>
    </div>

    <StateBlock v-else-if="errorMessage" :error="errorMessage" :empty="false" @retry="loadItems" />

    <div v-else-if="!items.length" class="empty-mall">
      <el-empty :description="hasFilters ? '没有符合条件的商品' : '暂无上架商品'">
        <el-button v-if="hasFilters" @click="clearFilters">清空筛选</el-button>
        <el-button v-else type="primary" @click="loadItems">重新加载</el-button>
      </el-empty>
    </div>

    <template v-else>
      <div class="mall-grid">
        <article
          v-for="item in items"
          :key="item.id"
          class="mall-card"
          data-testid="mall-card"
          :data-item-id="item.id"
          :data-price="item.price"
        >
          <button type="button" class="cover-button" @click="goDetail(item)">
            <img
              v-if="item.image && !brokenImages[item.id]"
              :src="item.image"
              :alt="item.name"
              loading="lazy"
              @error="brokenImages[item.id] = true"
            />
            <span v-else class="cover-fallback">{{ initialOf(item.name) }}</span>
            <span class="category-badge">{{ item.category || '精选好物' }}</span>
            <span v-if="Number(item.stock) <= 0" class="sold-out">已售罄</span>
          </button>

          <div class="card-body">
            <router-link
              class="card-name"
              :to="{ name: 'item-detail', params: { id: item.id } }"
              :title="item.name"
            >
              {{ item.name }}
            </router-link>
            <div class="card-meta">
              <span>已售 {{ formatCount(item.sold) }}</span>
              <span>库存 {{ item.stock }}</span>
              <span v-if="item.brand" class="brand">{{ item.brand }}</span>
            </div>
            <router-link
              v-if="item.shopId"
              class="card-shop"
              :to="{ name: 'shop-detail', params: { id: item.shopId } }"
            >
              <el-icon><Goods /></el-icon>
              {{ shopLabel(item) }}
            </router-link>
            <span v-else class="card-shop self-run">
              <el-icon><Goods /></el-icon>
              {{ shopLabel(item) }}
            </span>

            <div class="card-foot">
              <span class="card-price">{{ formatPrice(item.price) }}</span>
              <el-button
                type="primary"
                size="small"
                :icon="ShoppingCart"
                :loading="addingId === item.id"
                :disabled="Number(item.stock) <= 0"
                @click="addToCart(item)"
              >
                {{ Number(item.stock) > 0 ? '加入购物车' : '已售罄' }}
              </el-button>
            </div>
          </div>
        </article>
      </div>

      <div v-if="total > PAGE_SIZE" class="pagination-wrap">
        <el-pagination
          background
          layout="prev, pager, next"
          :current-page="page"
          :page-size="PAGE_SIZE"
          :total="total"
          @current-change="changePage"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.mall-page {
  width: 100%;
}

.mall-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 22px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: var(--shadow-sm);
}

.keyword-input {
  flex: 1 1 260px;
  min-width: 220px;
}

.category-select {
  width: 190px;
  flex: none;
}

.price-range {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.price-input {
  width: 104px;
}

.price-sep {
  color: var(--faint);
}

.toolbar-count {
  margin-left: auto;
  padding: 0 6px;
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
}

.toolbar-count b {
  color: var(--brand-600);
  font-size: 14px;
  font-weight: 800;
}

.toolbar-count i {
  font-style: normal;
  color: var(--faint);
}

.mall-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.mall-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
  transition:
    transform 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.mall-card:hover {
  transform: translateY(-3px);
  border-color: var(--brand-200);
  box-shadow: 0 18px 36px rgba(37, 99, 235, 0.12);
}

.cover-button {
  position: relative;
  display: block;
  width: 100%;
  padding: 0;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  border: none;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
  cursor: pointer;
}

.cover-button img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.35s ease;
}

.mall-card:hover .cover-button img {
  transform: scale(1.04);
}

.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--brand-500);
  font-size: 46px;
  font-weight: 850;
}

.category-badge,
.sold-out {
  position: absolute;
  padding: 4px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  backdrop-filter: blur(8px);
}

.category-badge {
  top: 10px;
  left: 10px;
  background: rgba(15, 23, 42, 0.5);
  color: #fff;
}

.sold-out {
  top: 10px;
  right: 10px;
  background: rgba(15, 23, 42, 0.66);
  color: #fff;
}

.card-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
}

.card-name {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink);
  font-size: 14px;
  font-weight: 700;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  color: var(--faint);
  font-size: 11px;
}

.card-meta .brand {
  overflow: hidden;
  max-width: 100%;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-shop {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  overflow: hidden;
  color: var(--brand-600);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-shop.self-run {
  color: var(--muted);
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid #f0f6ff;
}

.card-price {
  color: var(--brand-600);
  font-size: 20px;
  font-weight: 850;
  letter-spacing: -0.03em;
  white-space: nowrap;
}

.skeleton-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.skeleton-cover {
  display: block;
  width: 100%;
  aspect-ratio: 4 / 3;
  border-radius: 0;
}

.skeleton-body {
  padding: 14px;
}

.empty-mall {
  padding: 26px 20px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  background: var(--surface);
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 26px;
}

@media (max-width: 1180px) {
  .mall-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .mall-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .toolbar-count {
    width: 100%;
    margin-left: 0;
  }
}

@media (max-width: 640px) {
  .mall-toolbar {
    gap: 8px;
    padding: 9px;
  }

  .keyword-input,
  .category-select,
  .price-range {
    flex: 1 1 100%;
    width: 100%;
  }

  .price-input {
    flex: 1;
    width: auto;
  }

  .mall-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
  }

  /* 375px：默认 32px 的分页按钮 + 外边距会把整行撑到 392px，压缩按钮与间距避免横向溢出 */
  .pagination-wrap :deep(.el-pagination) {
    --el-pagination-button-width: 26px;
    --el-pagination-button-height: 26px;
    --el-pagination-item-gap: 8px;
  }
}
</style>
