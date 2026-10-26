<script setup lang="ts">
import { computed, reactive } from 'vue'
import { ChatDotRound, Pointer, Shop as ShopIcon, StarFilled } from '@element-plus/icons-vue'
import type { FeedItem } from '@/api/types'
import { formatCount, formatRelativeTime, initialOf, splitImages } from '@/utils/format'

const props = defineProps<{ item: FeedItem }>()

const images = computed(() => splitImages(props.item.images).slice(0, 3))
const brokenImages = reactive<Record<number, boolean>>({})
const score = computed(() => Math.max(0, Math.min(5, Number(props.item.score) || 0)))
const authorText = computed(() => `同城用户 ${props.item.authorId || '—'}`)
const authorInitial = computed(() => initialOf(authorText.value))
</script>

<template>
  <article class="feed-card">
    <header class="feed-head">
      <el-avatar :size="42" class="author-avatar">{{ authorInitial }}</el-avatar>
      <div class="author-meta">
        <b>{{ authorText }}</b>
        <span>{{ formatRelativeTime(item.createTime) }} · 关注动态</span>
      </div>
      <router-link
        v-if="item.shopId"
        class="shop-link"
        :to="{ name: 'shop-detail', params: { id: item.shopId } }"
      >
        <el-icon><ShopIcon /></el-icon>
        查看门店
      </router-link>
    </header>

    <div class="feed-content">
      <h3>{{ item.title || '同城生活分享' }}</h3>
      <p v-if="item.content">{{ item.content }}</p>
    </div>

    <div v-if="images.length" class="feed-images" :class="`count-${images.length}`">
      <div v-for="(image, index) in images" :key="`${image}-${index}`" class="feed-image">
        <img
          v-if="!brokenImages[index]"
          :src="image"
          alt="动态配图"
          loading="lazy"
          @error="brokenImages[index] = true"
        />
        <div v-else class="image-fallback">图片加载失败</div>
      </div>
    </div>

    <footer class="feed-foot">
      <div class="rating" :aria-label="`评分 ${score} 分`">
        <span v-for="index in 5" :key="index" class="star" :class="{ on: index <= Math.round(score) }">
          <el-icon><StarFilled /></el-icon>
        </span>
        <b>{{ score ? score.toFixed(1) : '—' }}</b>
      </div>
      <div class="engagement">
        <span><el-icon><Pointer /></el-icon>{{ formatCount(item.liked) }}</span>
        <span><el-icon><ChatDotRound /></el-icon>{{ formatCount(item.comments) }}</span>
      </div>
    </footer>
  </article>
</template>

<style scoped>
.feed-card {
  padding: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.feed-card:hover {
  border-color: var(--brand-200);
  box-shadow: 0 16px 36px rgba(37, 99, 235, 0.09);
}

.feed-head {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}

.author-avatar {
  background: linear-gradient(135deg, #dbeafe, #93c5fd);
  color: var(--brand-700);
  font-weight: 800;
  flex: none;
}

.author-meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.author-meta b {
  overflow: hidden;
  font-size: 13.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.author-meta span {
  color: var(--faint);
  font-size: 12px;
}

.shop-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border-radius: 999px;
  background: var(--brand-50);
  color: var(--brand-600);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  transition: background-color 0.16s ease;
}

.shop-link:hover {
  background: var(--brand-100);
}

.feed-content {
  margin-top: 14px;
}

.feed-content h3 {
  margin: 0;
  color: var(--ink);
  font-size: 18px;
  font-weight: 750;
  line-height: 1.42;
  letter-spacing: -0.02em;
}

.feed-content p {
  display: -webkit-box;
  margin: 7px 0 0;
  overflow: hidden;
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-line;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}

.feed-images {
  display: grid;
  gap: 6px;
  margin-top: 14px;
  border-radius: 12px;
  overflow: hidden;
}

.feed-images.count-1 {
  grid-template-columns: minmax(0, 1fr);
  max-width: 440px;
}

.feed-images.count-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.feed-images.count-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.feed-image {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: var(--brand-50);
}

.feed-images.count-1 .feed-image {
  aspect-ratio: 16 / 9;
}

.feed-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}

.feed-image:hover img {
  transform: scale(1.03);
}

.image-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--faint);
  font-size: 12px;
  background: linear-gradient(135deg, #eff6ff, #dbeafe);
}

.feed-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #f0f6ff;
}

.rating {
  display: flex;
  align-items: center;
  gap: 2px;
}

.rating .star {
  color: #dbe7f8;
  font-size: 14px;
  line-height: 1;
}

.rating .star.on {
  color: #f3b33d;
}

.rating b {
  margin-left: 5px;
  color: var(--gold);
  font-size: 12.5px;
}

.engagement {
  display: flex;
  align-items: center;
  gap: 14px;
  color: var(--faint);
  font-size: 12.5px;
}

.engagement span {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.engagement .el-icon {
  font-size: 14px;
}

@media (max-width: 640px) {
  .feed-card {
    padding: 16px;
  }

  .feed-content h3 {
    font-size: 16.5px;
  }

  .feed-images.count-3 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
