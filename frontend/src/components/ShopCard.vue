<script setup lang="ts">
import { computed, ref } from 'vue'
import { Location, StarFilled } from '@element-plus/icons-vue'
import type { Shop } from '@/api/types'
import { formatCount, formatPrice, initialOf, scoreOf, splitImages } from '@/utils/format'

const props = defineProps<{ shop: Shop }>()

const cover = computed(() => splitImages(props.shop.images)[0] || '')
const coverFailed = ref(false)
const score = computed(() => scoreOf(props.shop.score))
</script>

<template>
  <router-link
    class="shop-card"
    :to="{ name: 'shop-detail', params: { id: shop.id } }"
    :aria-label="`查看门店 ${shop.name}`"
  >
    <div class="shop-cover">
      <img
        v-if="cover && !coverFailed"
        :src="cover"
        :alt="shop.name"
        loading="lazy"
        @error="coverFailed = true"
      />
      <div v-else class="cover-fallback" aria-hidden="true">
        <span>{{ initialOf(shop.name) }}</span>
      </div>
      <span class="type-badge">{{ shop.typeName || '精选门店' }}</span>
      <span v-if="shop.hotScore" class="hot-badge">
        <el-icon><StarFilled /></el-icon>
        热度 {{ Number(shop.hotScore).toFixed(1) }}
      </span>
    </div>

    <div class="shop-body">
      <h3>{{ shop.name }}</h3>
      <div class="shop-meta">
        <span class="score"><el-icon><StarFilled /></el-icon>{{ score }}</span>
        <span>{{ formatCount(shop.comments) }} 条评价</span>
        <span v-if="shop.area" class="area">{{ shop.area }}</span>
      </div>
      <div class="shop-foot">
        <span class="avg">人均 {{ formatPrice(shop.avgPrice) }}</span>
        <span class="address">
          <el-icon><Location /></el-icon>
          {{ shop.address || '地址待完善' }}
        </span>
      </div>
    </div>
  </router-link>
</template>

<style scoped>
.shop-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    border-color 0.2s ease;
}

.shop-card:hover {
  transform: translateY(-3px);
  border-color: var(--brand-200);
  box-shadow: 0 20px 40px rgba(37, 99, 235, 0.13);
}

.shop-cover {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background: linear-gradient(135deg, #dbeafe, #eff6ff);
}

.shop-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.35s ease;
}

.shop-card:hover .shop-cover img {
  transform: scale(1.04);
}

.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background:
    radial-gradient(circle at 20% 20%, rgba(255, 255, 255, 0.7), transparent 50%),
    linear-gradient(135deg, #bfdbfe 0%, #3b82f6 100%);
}

.cover-fallback span {
  color: rgba(255, 255, 255, 0.92);
  font-size: 54px;
  font-weight: 800;
  line-height: 1;
  text-shadow: 0 8px 24px rgba(30, 64, 175, 0.25);
}

.type-badge,
.hot-badge {
  position: absolute;
  top: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  color: #fff;
  font-size: 11.5px;
  font-weight: 700;
  backdrop-filter: blur(8px);
}

.type-badge {
  left: 12px;
  background: rgba(15, 23, 42, 0.48);
}

.hot-badge {
  right: 12px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  background: rgba(217, 119, 6, 0.88);
  font-size: 10.5px;
}

.hot-badge .el-icon {
  font-size: 11px;
}

.shop-body {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 15px 16px 16px;
}

h3 {
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  font-size: 16.5px;
  font-weight: 750;
  line-height: 1.35;
  letter-spacing: -0.015em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-meta,
.shop-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--muted);
  font-size: 12.5px;
}

.score {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--gold);
  font-weight: 700;
}

.area {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-foot {
  justify-content: space-between;
  padding-top: 9px;
  border-top: 1px solid #f0f6ff;
}

.avg {
  color: var(--brand-600);
  font-weight: 700;
  white-space: nowrap;
}

.address {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  overflow: hidden;
  color: var(--faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.address .el-icon {
  flex: none;
}
</style>
