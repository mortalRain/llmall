<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Star, StarFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import ShopCard from '@/components/ShopCard.vue'
import StateBlock from '@/components/StateBlock.vue'
import { favoriteApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import type { Shop } from '@/api/types'

const router = useRouter()

const shops = ref<Shop[]>([])
const loading = ref(true)
const errorMessage = ref('')
const removingId = ref('')

async function loadFavorites(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    shops.value = await favoriteApi.my()
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '收藏加载失败')
    shops.value = []
  } finally {
    loading.value = false
  }
}

async function cancelFavorite(shop: Shop): Promise<void> {
  if (removingId.value) return
  removingId.value = shop.id
  try {
    await favoriteApi.remove(shop.id)
    shops.value = shops.value.filter((item) => String(item.id) !== String(shop.id))
    ElMessage.success(`已取消收藏「${shop.name}」`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '取消收藏失败'))
  } finally {
    removingId.value = ''
  }
}

function goShops(): void {
  void router.push('/shops')
}

onMounted(() => {
  void loadFavorites()
})
</script>

<template>
  <div class="favorites-page">
    <PageHeader
      eyebrow="MY FAVORITES"
      title="我的收藏"
      subtitle="收藏的门店会按收藏时间倒序排列，点卡片进店，也可以随时取消收藏。"
    >
      <template #actions>
        <el-button :icon="Star" @click="goShops">去逛门店</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadFavorites">刷新收藏</el-button>
      </template>
    </PageHeader>

    <StateBlock
      :loading="loading"
      :error="errorMessage"
      :empty="!loading && !errorMessage && !shops.length"
      empty-text="还没有收藏门店，去门店详情点一下「收藏门店」吧"
      @retry="loadFavorites"
    >
      <template #empty-action>
        <el-button type="primary" @click="goShops">去逛门店</el-button>
      </template>

      <div class="favorites-head">
        <span class="favorites-count">
          <el-icon><StarFilled /></el-icon>
          共 <b>{{ shops.length }}</b> 家收藏门店
        </span>
        <span class="favorites-hint">收藏是个人数据，仅自己可见</span>
      </div>

      <div class="favorites-grid">
        <article
          v-for="shop in shops"
          :key="shop.id"
          class="favorite-card"
          data-testid="favorite-card"
          :data-shop-id="shop.id"
        >
          <ShopCard :shop="shop" />
          <div class="favorite-actions">
            <span class="favorite-time">已收藏</span>
            <el-button
              size="small"
              type="danger"
              plain
              :icon="StarFilled"
              :loading="removingId === shop.id"
              @click="cancelFavorite(shop)"
            >
              取消收藏
            </el-button>
          </div>
        </article>
      </div>
    </StateBlock>
  </div>
</template>

<style scoped>
.favorites-page {
  width: 100%;
}

.favorites-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  color: var(--muted);
  font-size: 12px;
}

.favorites-count {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--brand-600);
  font-weight: 600;
}

.favorites-count b {
  font-size: 15px;
  font-weight: 850;
}

.favorites-hint {
  color: var(--faint);
  font-size: 11px;
}

.favorites-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}

.favorite-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--surface);
}

.favorite-card :deep(.shop-card) {
  flex: 1;
  border-bottom-left-radius: 0;
  border-bottom-right-radius: 0;
}

.favorite-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 14px;
  border: 1px solid var(--line);
  border-top: none;
  border-radius: 0 0 var(--radius) var(--radius);
  background: var(--surface-soft);
}

.favorite-time {
  color: var(--faint);
  font-size: 11px;
}

@media (max-width: 1024px) {
  .favorites-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .favorites-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .favorites-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
  }
}
</style>
