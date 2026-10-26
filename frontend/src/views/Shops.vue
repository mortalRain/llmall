<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import ShopCard from '@/components/ShopCard.vue'
import StateBlock from '@/components/StateBlock.vue'
import { shopApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import type { Shop } from '@/api/types'

const PAGE_SIZE = 9

const shops = ref<Shop[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const appliedKeyword = ref('')
const loading = ref(true)
const errorMessage = ref('')

async function loadShops(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await shopApi.page({
      page: page.value,
      size: PAGE_SIZE,
      keyword: appliedKeyword.value || undefined,
    })
    shops.value = Array.isArray(result.list) ? result.list : []
    total.value = Number(result.total || 0)
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '门店加载失败')
    shops.value = []
  } finally {
    loading.value = false
  }
}

function applySearch(): void {
  appliedKeyword.value = keyword.value.trim()
  page.value = 1
  void loadShops()
}

function clearSearch(): void {
  keyword.value = ''
  applySearch()
}

function changePage(nextPage: number): void {
  page.value = nextPage
  void loadShops()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  void loadShops()
})
</script>

<template>
  <div class="shops-page">
    <PageHeader
      eyebrow="DISCOVER NEARBY"
      title="逛门店"
      subtitle="按热度与评价发现同城好店，进店选套餐、领券，再下单到店享受。"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="loadShops">刷新门店</el-button>
      </template>
    </PageHeader>

    <div class="shop-toolbar">
      <el-input
        v-model="keyword"
        size="large"
        clearable
        :prefix-icon="Search"
        placeholder="搜索门店名称，例如：海底捞"
        @keyup.enter="applySearch"
        @clear="applySearch"
      />
      <el-button type="primary" size="large" :icon="Search" @click="applySearch">搜索</el-button>
      <span class="toolbar-count">
        共 <b>{{ total }}</b> 家门店
        <i v-if="appliedKeyword">· “{{ appliedKeyword }}”</i>
      </span>
    </div>

    <div v-if="loading" class="shops-grid">
      <div v-for="index in 6" :key="index" class="skeleton-shop">
        <el-skeleton-item variant="image" class="skeleton-cover" />
        <div class="skeleton-body">
          <el-skeleton :rows="2" animated />
        </div>
      </div>
    </div>

    <StateBlock
      v-else-if="errorMessage"
      :error="errorMessage"
      :empty="false"
      @retry="loadShops"
    />

    <div v-else-if="!shops.length" class="empty-shops">
      <el-empty :description="appliedKeyword ? `没有找到与“${appliedKeyword}”相关的门店` : '暂无营业中的门店'">
        <el-button v-if="appliedKeyword" @click="clearSearch">清除搜索</el-button>
        <el-button v-else type="primary" @click="loadShops">重新加载</el-button>
      </el-empty>
    </div>

    <template v-else>
      <div class="shops-grid">
        <ShopCard v-for="shop in shops" :key="shop.id" :shop="shop" />
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
.shops-page {
  width: 100%;
}

.shop-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 22px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: var(--shadow-sm);
}

.shop-toolbar :deep(.el-input) {
  flex: 1;
  min-width: 0;
}

.shop-toolbar :deep(.el-input__wrapper) {
  background: transparent;
  box-shadow: none;
}

.toolbar-count {
  flex: none;
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

.shops-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}

.skeleton-shop {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.skeleton-cover {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: 0;
}

.skeleton-body {
  padding: 16px;
}

.empty-shops {
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

@media (max-width: 1024px) {
  .shops-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .shop-toolbar {
    flex-wrap: wrap;
    padding: 8px;
  }

  .shop-toolbar :deep(.el-input) {
    flex-basis: calc(100% - 78px);
  }

  .shop-toolbar :deep(.el-button) {
    flex: none;
    padding: 0 14px;
  }

  .toolbar-count {
    width: 100%;
    padding: 2px 6px;
    font-size: 11.5px;
  }

  .shops-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
  }

  .pagination-wrap :deep(.el-pagination) {
    --el-pagination-button-width: 26px;
    --el-pagination-button-height: 26px;
    --el-pagination-item-gap: 8px;
  }
}
</style>
