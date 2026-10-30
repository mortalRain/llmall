<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Refresh, Search, TrendCharts, Wallet } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import FeedCard from '@/components/FeedCard.vue'
import { feedApi, shopApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { FeedCursor, FeedItem, FeedQuery, Shop } from '@/api/types'
import { formatMoney } from '@/utils/format'
import { feedHasMore } from '@/utils/feed'

const PAGE_SIZE = 10

const router = useRouter()
const auth = useAuthStore()

const items = ref<FeedItem[]>([])
const cursor = ref<FeedCursor>({})
const loading = ref(true)
const loadingMore = ref(false)
const errorMessage = ref('')
const hasMore = ref(false)
const keyword = ref('')

const hotShops = ref<Shop[]>([])
const hotLoading = ref(true)
const hotError = ref('')

const filteredItems = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return items.value
  return items.value.filter((item) => {
    return [item.title, item.content, item.authorId]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(query))
  })
})

const searchHint = computed(() => {
  if (!keyword.value.trim()) return `已加载 ${items.value.length} 条动态`
  return `在已加载的 ${items.value.length} 条动态中匹配到 ${filteredItems.value.length} 条`
})

async function loadFeed(reset = false): Promise<void> {
  if (loadingMore.value) return
  if (reset) {
    loading.value = true
    errorMessage.value = ''
    cursor.value = {}
    hasMore.value = false
  } else {
    if (!hasMore.value) return
    loadingMore.value = true
  }

  try {
    const params: FeedQuery = { size: PAGE_SIZE }
    if (!reset) {
      if (cursor.value.li) params.li = cursor.value.li
      if (cursor.value.ct) params.ct = cursor.value.ct
      if (cursor.value.ci) params.ci = cursor.value.ci
      if (cursor.value.ai) params.ai = cursor.value.ai
    }
    const page = await feedApi.getFeed(params)
    const list = Array.isArray(page.items) ? page.items : []
    items.value = reset ? list : [...items.value, ...list]
    cursor.value = {
      li: page.nextLi ?? undefined,
      ct: page.nextCt ?? undefined,
      ci: page.nextCi ?? undefined,
      ai: page.nextAuthorId ?? undefined,
    }
    hasMore.value = feedHasMore(page, PAGE_SIZE)
  } catch (error) {
    if (reset) {
      errorMessage.value = getErrorMessage(error, '动态加载失败')
    } else {
      ElMessage.error(getErrorMessage(error, '加载更多失败'))
    }
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function loadHotShops(): Promise<void> {
  hotLoading.value = true
  hotError.value = ''
  try {
    hotShops.value = await shopApi.hot(5)
  } catch (error) {
    hotError.value = getErrorMessage(error, '热门门店加载失败')
  } finally {
    hotLoading.value = false
  }
}

function loadMore(): void {
  void loadFeed(false)
}

function refresh(): void {
  void loadFeed(true)
}

function goShops(): void {
  void router.push('/shops')
}

onMounted(() => {
  void loadFeed(true)
  void loadHotShops()
})
</script>

<template>
  <div class="feed-page">
    <PageHeader
      eyebrow="FOLLOWING FEED"
      title="关注动态"
      subtitle="同城作者正在分享什么？关注他们，就能在这里看到最新的探店与生活灵感。"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新动态</el-button>
      </template>
    </PageHeader>

    <div class="search-bar">
      <el-input
        v-model="keyword"
        size="large"
        clearable
        :prefix-icon="Search"
        placeholder="在已加载的动态中搜索标题、正文或作者 ID"
      />
      <span class="search-hint">{{ searchHint }}</span>
    </div>

    <div class="feed-layout">
      <div class="feed-main">
        <div v-if="loading" class="feed-skeletons">
          <div v-for="index in 3" :key="index" class="skeleton-card">
            <div class="skeleton-head">
              <el-skeleton-item variant="circle" style="width: 42px; height: 42px" />
              <el-skeleton :rows="1" animated style="flex: 1" />
            </div>
            <el-skeleton :rows="3" animated />
          </div>
        </div>

        <div v-else-if="errorMessage" class="error-card">
          <div class="error-icon">!</div>
          <h3>动态暂时加载不出来</h3>
          <p>{{ errorMessage }}</p>
          <el-button type="primary" :icon="Refresh" @click="refresh">重新加载</el-button>
        </div>

        <div v-else-if="!items.length" class="empty-card">
          <div class="empty-art">
            <span class="empty-ring"></span>
            <span class="empty-avatar">L</span>
          </div>
          <h3>{{ hasMore ? '这页暂无可展示的动态' : '还没有关注动态' }}</h3>
          <p>{{ hasMore ? '可以继续加载后面的动态。' : '关注同城作者后，他们的探店笔记会出现在这里。先去发现几家好店吧。' }}</p>
          <el-button type="primary" @click="goShops">
            去逛门店
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>

        <div v-else-if="!filteredItems.length" class="empty-card compact">
          <h3>没有找到匹配的动态</h3>
          <p>当前已加载 {{ items.length }} 条动态，换个关键词试试。</p>
          <el-button @click="keyword = ''">清除搜索</el-button>
        </div>

        <div v-else class="feed-list">
          <FeedCard v-for="item in filteredItems" :key="item.blogId" :item="item" />
        </div>

        <div v-if="!loading && !errorMessage && hasMore && !keyword" class="load-more">
          <el-button :loading="loadingMore" @click="loadMore">
            {{ loadingMore ? '正在加载…' : '加载更多动态' }}
          </el-button>
        </div>
      </div>

      <aside class="feed-rail">
        <div class="account-card">
          <div class="account-head">
            <el-avatar :size="44" class="account-avatar">{{ auth.avatarText }}</el-avatar>
            <div>
              <b>{{ auth.displayName }}</b>
              <span>欢迎回来</span>
            </div>
          </div>
          <div class="account-stats">
            <div>
              <span>账户余额（元）</span>
              <b>{{ formatMoney(auth.balance) }}</b>
            </div>
            <div>
              <span>我的积分</span>
              <b>{{ auth.credits }}</b>
            </div>
          </div>
          <div class="account-links">
            <router-link to="/orders">
              我的订单
              <el-icon><ArrowRight /></el-icon>
            </router-link>
            <router-link to="/profile">
              优惠券
              <el-icon><ArrowRight /></el-icon>
            </router-link>
          </div>
        </div>

        <div class="rail-card">
          <div class="rail-head">
            <div>
              <el-icon class="rail-icon"><TrendCharts /></el-icon>
              <b>热门门店</b>
            </div>
            <router-link to="/shops">查看全部</router-link>
          </div>

          <div v-if="hotLoading" class="rail-skeleton">
            <el-skeleton :rows="4" animated />
          </div>
          <div v-else-if="hotError" class="rail-error">{{ hotError }}</div>
          <ol v-else-if="hotShops.length" class="hot-list">
            <li v-for="(shop, index) in hotShops" :key="shop.id">
              <router-link :to="{ name: 'shop-detail', params: { id: shop.id } }">
                <span class="rank" :class="{ top: index < 3 }">{{ index + 1 }}</span>
                <span class="hot-info">
                  <b>{{ shop.name }}</b>
                  <small>{{ shop.typeName || '精选门店' }} · {{ shop.area || '同城' }}</small>
                </span>
                <span class="hot-score">{{ Number(shop.hotScore || 0).toFixed(1) }}</span>
              </router-link>
            </li>
          </ol>
          <div v-else class="rail-empty">暂无热门门店</div>
        </div>

        <div class="rail-tip">
          <el-icon><Wallet /></el-icon>
          <div>
            <b>余额支付更省心</b>
            <span>下单后用余额支付，到店出示核销码即可使用。</span>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.feed-page {
  width: 100%;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 22px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: var(--shadow-sm);
}

.search-bar :deep(.el-input__wrapper) {
  background: transparent;
  box-shadow: none;
}

.search-bar :deep(.el-input__inner) {
  font-size: 14px;
}

.search-hint {
  flex: none;
  padding-right: 6px;
  color: var(--faint);
  font-size: 12px;
  white-space: nowrap;
}

.feed-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 310px;
  align-items: start;
  gap: 22px;
}

.feed-main {
  min-width: 0;
}

.feed-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.feed-skeletons {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.skeleton-card {
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.skeleton-head {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 18px;
}

.error-card,
.empty-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 360px;
  padding: 40px 26px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  background:
    radial-gradient(circle at 50% 0, rgba(219, 234, 254, 0.55), transparent 55%),
    var(--surface);
  text-align: center;
}

.empty-card.compact {
  min-height: 250px;
}

.error-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 54px;
  height: 54px;
  border-radius: 50%;
  background: #fff1f2;
  color: #e11d48;
  font-size: 26px;
  font-weight: 800;
}

.error-card h3,
.empty-card h3 {
  margin: 16px 0 0;
  color: var(--ink);
  font-size: 19px;
  font-weight: 750;
}

.error-card p,
.empty-card p {
  max-width: 380px;
  margin: 8px 0 18px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}

.empty-art {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 104px;
  height: 104px;
}

.empty-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1px dashed var(--brand-200);
  background: radial-gradient(circle, #eff6ff, transparent 68%);
}

.empty-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 18px;
  background: linear-gradient(135deg, var(--brand-500), #93c5fd);
  color: #fff;
  font-size: 27px;
  font-weight: 850;
  box-shadow: 0 12px 26px rgba(59, 130, 246, 0.24);
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 22px 0 8px;
}

.feed-rail {
  position: sticky;
  top: 88px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.account-card {
  padding: 18px;
  border-radius: var(--radius);
  background:
    radial-gradient(circle at 100% 0, rgba(255, 255, 255, 0.35), transparent 50%),
    linear-gradient(145deg, #2563eb 0%, #3b82f6 55%, #7db8ff 100%);
  color: #fff;
  box-shadow: 0 18px 36px rgba(37, 99, 235, 0.22);
}

.account-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.account-avatar {
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
  font-weight: 800;
  border: 1px solid rgba(255, 255, 255, 0.36);
}

.account-head > div {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.account-head b {
  overflow: hidden;
  font-size: 15px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-head span {
  color: rgba(255, 255, 255, 0.72);
  font-size: 11.5px;
}

.account-stats {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid rgba(255, 255, 255, 0.2);
}

.account-stats div {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.account-stats span {
  color: rgba(255, 255, 255, 0.68);
  font-size: 10.5px;
}

.account-stats b {
  overflow: hidden;
  margin-top: 2px;
  font-size: 18px;
  font-weight: 800;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-links {
  display: flex;
  gap: 8px;
  margin-top: 15px;
}

.account-links a {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  font-size: 11.5px;
  font-weight: 600;
}

.account-links a:hover {
  background: rgba(255, 255, 255, 0.26);
}

.rail-card {
  padding: 17px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.rail-head > div {
  display: flex;
  align-items: center;
  gap: 6px;
}

.rail-icon {
  color: var(--brand-500);
}

.rail-head b {
  font-size: 14px;
  font-weight: 750;
}

.rail-head > a {
  color: var(--brand-600);
  font-size: 11.5px;
  font-weight: 600;
}

.hot-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.hot-list li + li {
  margin-top: 2px;
}

.hot-list a {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 6px;
  border-radius: 9px;
}

.hot-list a:hover {
  background: var(--brand-50);
}

.rank {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  flex: none;
  border-radius: 7px;
  background: #f1f5f9;
  color: var(--muted);
  font-size: 11px;
  font-weight: 750;
}

.rank.top {
  background: #fff7ed;
  color: #c2410c;
}

.hot-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.hot-info b {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-info small {
  overflow: hidden;
  color: var(--faint);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-score {
  flex: none;
  color: var(--gold);
  font-size: 11.5px;
  font-weight: 750;
}

.rail-skeleton {
  padding: 4px 2px;
}

.rail-error,
.rail-empty {
  padding: 18px 0;
  color: var(--faint);
  font-size: 12px;
  text-align: center;
}

.rail-tip {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 14px;
  border-radius: 12px;
  background: var(--brand-50);
  border: 1px dashed var(--brand-200);
  color: var(--brand-600);
}

.rail-tip > .el-icon {
  margin-top: 2px;
  flex: none;
}

.rail-tip b {
  display: block;
  font-size: 12.5px;
}

.rail-tip span {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.55;
}

@media (max-width: 1100px) {
  .feed-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .feed-rail {
    position: static;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .rail-tip {
    display: none;
  }
}

@media (max-width: 640px) {
  .search-bar {
    flex-wrap: wrap;
    gap: 4px;
    padding: 8px;
  }

  .search-hint {
    width: 100%;
    padding: 0 6px 2px;
    font-size: 11px;
  }

  .feed-rail {
    display: flex;
  }

  .account-stats b {
    font-size: 16px;
  }

  .error-card,
  .empty-card {
    min-height: 300px;
    padding: 30px 18px;
  }
}
</style>
