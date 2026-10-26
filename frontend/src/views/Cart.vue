<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Delete,
  Goods,
  Refresh,
  ShoppingCart,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import { cartApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import type { CartItem } from '@/api/types'
import { formatMoney, formatPrice, formatSpec, initialOf } from '@/utils/format'

const CHECKOUT_STORAGE_KEY = 'llmall.checkout.lines'

const router = useRouter()

const carts = ref<CartItem[]>([])
const loading = ref(true)
const errorMessage = ref('')
const selectedIds = reactive(new Set<string>())
const savingIds = reactive(new Set<string>())
const removingIds = reactive(new Set<string>())
const brokenImages = reactive<Record<string, boolean>>({})

const selectedRows = computed(() =>
  carts.value.filter((cart) => selectedIds.has(String(cart.id))),
)

/** 可勾选的条目：失效率商品不参与全选，否则"全选"永远无法变成已全选，也清不掉已选项 */
const selectableCarts = computed(() => carts.value.filter((cart) => cartAvailable(cart)))

const allSelected = computed(
  () =>
    selectableCarts.value.length > 0 &&
    selectableCarts.value.every((cart) => selectedIds.has(String(cart.id))),
)

/**
 * 结算单价：优先用商品当前价格（newPrice）。购物车里的 price 是加入时快照，
 * 商品调价后若继续按快照算合计，用户会看到"购物车合计 ≠ 结算页应付"。
 */
function priceOf(cart: CartItem): number {
  const current = cart.newPrice
  return Number(current === null || current === undefined ? cart.price : current)
}

const totalFen = computed(() =>
  selectedRows.value.reduce(
    (sum, cart) => sum + priceOf(cart) * Number(cart.num || 0),
    0,
  ),
)

const selectedCount = computed(() =>
  selectedRows.value.reduce((sum, cart) => sum + Number(cart.num || 0), 0),
)

function cartAvailable(cart: CartItem): boolean {
  return Number(cart.stock ?? 1) > 0 && Number(cart.status ?? 1) === 1
}

async function loadCarts(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    carts.value = await cartApi.list()
    selectedIds.clear()
    carts.value.forEach((cart) => {
      if (cartAvailable(cart)) selectedIds.add(String(cart.id))
    })
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '购物车加载失败')
    carts.value = []
  } finally {
    loading.value = false
  }
}

function toggleOne(cart: CartItem): void {
  const id = String(cart.id)
  if (selectedIds.has(id)) selectedIds.delete(id)
  else selectedIds.add(id)
}

function toggleAll(): void {
  if (allSelected.value) {
    selectedIds.clear()
    return
  }
  selectableCarts.value.forEach((cart) => selectedIds.add(String(cart.id)))
}

async function changeNum(cart: CartItem, value: number | undefined): Promise<void> {
  const num = Number(value || 0)
  if (!Number.isInteger(num) || num < 1) {
    await loadCarts()
    return
  }
  const id = String(cart.id)
  savingIds.add(id)
  try {
    await cartApi.updateNum(id, num)
    cart.num = num
    ElMessage.success('数量已更新')
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '修改数量失败'))
    await loadCarts()
  } finally {
    savingIds.delete(id)
  }
}

async function removeCart(cart: CartItem): Promise<void> {
  const id = String(cart.id)
  if (removingIds.has(id)) return
  removingIds.add(id)
  try {
    await cartApi.remove(id)
    carts.value = carts.value.filter((row) => String(row.id) !== id)
    selectedIds.delete(id)
    ElMessage.success('已从购物车删除')
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '删除失败'))
  } finally {
    removingIds.delete(id)
  }
}

function goMall(): void {
  void router.push('/mall')
}

function goItem(cart: CartItem): void {
  void router.push({ name: 'item-detail', params: { id: cart.itemId } })
}

function goCheckout(): void {
  if (!selectedRows.value.length) {
    ElMessage.warning('请先选择要结算的商品')
    return
  }
  const lines = selectedRows.value.map((cart) => ({
    cartId: String(cart.id),
    itemId: String(cart.itemId),
    num: Number(cart.num),
  }))
  try {
    sessionStorage.setItem(CHECKOUT_STORAGE_KEY, JSON.stringify(lines))
  } catch {
    ElMessage.error('无法保存结算商品，请检查浏览器存储设置')
    return
  }
  void router.push({ name: 'checkout', query: { from: 'cart' } })
}

onMounted(() => {
  void loadCarts()
})
</script>

<template>
  <div class="cart-page">
    <PageHeader
      eyebrow="SHOPPING CART"
      title="购物车"
      subtitle="勾选要结算的商品，调整数量后一次下单；多商品会合并为一笔订单。"
    >
      <template #actions>
        <el-button :icon="Goods" @click="goMall">继续逛逛</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadCarts">刷新购物车</el-button>
      </template>
    </PageHeader>

    <StateBlock
      :loading="loading"
      :error="errorMessage"
      :empty="!loading && !errorMessage && !carts.length"
      empty-text="购物车还是空的，去商城挑点喜欢的吧"
      @retry="loadCarts"
    >
      <template #empty-action>
        <el-button type="primary" @click="goMall">去商城逛逛</el-button>
      </template>

      <div v-if="carts.length" class="cart-layout">
        <section class="cart-list">
          <div class="cart-head">
            <el-checkbox
              :model-value="allSelected"
              :indeterminate="selectedIds.size > 0 && !allSelected"
              @change="toggleAll"
            >
              全选
            </el-checkbox>
            <span>商品信息</span>
            <span>单价</span>
            <span>数量</span>
            <span>小计</span>
            <span>操作</span>
          </div>

          <article
            v-for="cart in carts"
            :key="cart.id"
            class="cart-row"
            data-testid="cart-row"
            :data-cart-id="cart.id"
            :data-item-id="cart.itemId"
            :data-price="priceOf(cart)"
            :class="{ disabled: !cartAvailable(cart) }"
          >
            <el-checkbox
              :model-value="selectedIds.has(String(cart.id))"
              :disabled="!cartAvailable(cart)"
              @change="toggleOne(cart)"
            />

            <div class="cart-goods">
              <button type="button" class="cart-thumb" @click="goItem(cart)">
                <img
                  v-if="cart.image && !brokenImages[cart.id]"
                  :src="cart.image"
                  :alt="cart.name"
                  loading="lazy"
                  @error="brokenImages[cart.id] = true"
                />
                <span v-else class="thumb-fallback">{{ initialOf(cart.name) }}</span>
              </button>
              <div class="cart-info">
                <router-link
                  class="cart-name"
                  :to="{ name: 'item-detail', params: { id: cart.itemId } }"
                >
                  {{ cart.name }}
                </router-link>
                <p>{{ formatSpec(cart.spec) || '默认规格' }}</p>
                <el-tag v-if="!cartAvailable(cart)" size="small" type="info">已失效</el-tag>
              </div>
            </div>

            <div class="cart-price">
              {{ formatPrice(priceOf(cart)) }}
              <small v-if="cart.newPrice && Number(cart.newPrice) !== Number(cart.price)">
                加入时 {{ formatPrice(cart.price) }}
              </small>
            </div>

            <div class="cart-qty">
              <el-input-number
                v-model="cart.num"
                :min="1"
                :max="99"
                size="small"
                :disabled="!cartAvailable(cart) || savingIds.has(String(cart.id))"
                @change="(value: number | undefined) => changeNum(cart, value)"
              />
            </div>

            <div class="cart-subtotal">{{ formatPrice(priceOf(cart) * Number(cart.num)) }}</div>

            <el-button
              text
              type="danger"
              :icon="Delete"
              :loading="removingIds.has(String(cart.id))"
              @click="removeCart(cart)"
            >
              删除
            </el-button>
          </article>
        </section>

        <aside class="cart-summary">
          <div class="summary-head">
            <span>已选 {{ selectedRows.length }} 种商品</span>
            <el-tag size="small" type="primary" effect="light">{{ selectedCount }} 件</el-tag>
          </div>
          <div class="summary-total">
            <span>合计</span>
            <b
              data-testid="cart-total"
              :data-total-fen="totalFen"
            >{{ formatMoney(totalFen) }}</b>
          </div>
          <p class="summary-hint">优惠券与地址在下一步选择，不改变商品单价。</p>
          <el-button
            type="primary"
            size="large"
            :icon="ShoppingCart"
            :disabled="!selectedRows.length"
            @click="goCheckout"
          >
            去结算（{{ selectedRows.length }}）
          </el-button>
          <p class="summary-note">
            提交订单后，订单中包含的商品会自动从购物车移除。
          </p>
        </aside>
      </div>
    </StateBlock>
  </div>
</template>

<style scoped>
.cart-page {
  width: 100%;
}

.cart-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  align-items: start;
  gap: 20px;
}

.cart-list {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.cart-head {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 96px 120px 96px 76px;
  align-items: center;
  column-gap: 14px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--surface-soft);
  color: var(--faint);
  font-size: 11.5px;
}

.cart-head > span:first-of-type {
  grid-column: 2;
  padding-left: 4px;
}

.cart-row {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 96px 120px 96px 76px;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid #f0f6ff;
}

.cart-row:last-child {
  border-bottom: none;
}

.cart-row.disabled {
  background: #fbfdff;
  opacity: 0.72;
}

.cart-goods {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}

.cart-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  flex: none;
  overflow: hidden;
  padding: 0;
  border: none;
  border-radius: 10px;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
  cursor: pointer;
}

.cart-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-fallback {
  color: var(--brand-500);
  font-size: 24px;
  font-weight: 850;
}

.cart-info {
  min-width: 0;
}

.cart-name {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink);
  font-size: 13.5px;
  font-weight: 700;
  line-height: 1.4;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.cart-info p {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cart-price {
  display: flex;
  flex-direction: column;
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 650;
}

.cart-price small {
  color: var(--faint);
  font-size: 10px;
  font-weight: 500;
}

.cart-qty :deep(.el-input-number) {
  width: 106px;
}

.cart-subtotal {
  color: var(--brand-600);
  font-size: 15px;
  font-weight: 800;
  white-space: nowrap;
}

.cart-summary {
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
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #f0f6ff;
  color: var(--muted);
  font-size: 12.5px;
}

.summary-total b {
  color: var(--brand-600);
  font-size: 30px;
  font-weight: 850;
  letter-spacing: -0.04em;
}

.summary-hint {
  margin: 5px 0 0;
  color: var(--faint);
  font-size: 10.5px;
}

.cart-summary :deep(.el-button) {
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

@media (max-width: 1024px) {
  .cart-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .cart-summary {
    position: static;
  }

  .cart-head {
    display: none;
  }

  .cart-row {
    grid-template-columns: 30px minmax(0, 1fr) auto;
    grid-template-areas:
      'check goods remove'
      'check price qty'
      'check subtotal subtotal';
    gap: 8px 10px;
    padding: 12px;
  }

  .cart-row > :deep(.el-checkbox) {
    grid-area: check;
    align-self: start;
  }

  .cart-goods {
    grid-area: goods;
  }

  .cart-price {
    grid-area: price;
  }

  .cart-qty {
    grid-area: qty;
    justify-self: end;
  }

  .cart-subtotal {
    grid-area: subtotal;
  }

  .cart-row > :deep(.el-button) {
    grid-area: remove;
    justify-self: end;
  }
}

@media (max-width: 640px) {
  .cart-thumb {
    width: 62px;
    height: 62px;
  }

  .cart-summary {
    padding: 16px;
  }

  .summary-total b {
    font-size: 26px;
  }
}
</style>
