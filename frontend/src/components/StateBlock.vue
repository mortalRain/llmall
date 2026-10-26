<script setup lang="ts">
withDefaults(
  defineProps<{
    loading?: boolean
    error?: string
    empty?: boolean
    emptyText?: string
    loadingRows?: number
    compact?: boolean
  }>(),
  {
    loading: false,
    error: '',
    empty: false,
    emptyText: '暂无数据',
    loadingRows: 5,
    compact: false,
  },
)

const emit = defineEmits<{ (event: 'retry'): void }>()
</script>

<template>
  <div v-if="loading" class="state-block" :class="{ compact }">
    <el-skeleton :rows="loadingRows" animated />
  </div>
  <div v-else-if="error" class="state-block" :class="{ compact }">
    <el-result icon="error" title="加载失败" :sub-title="error">
      <template #extra>
        <el-button type="primary" @click="emit('retry')">重新加载</el-button>
      </template>
    </el-result>
  </div>
  <div v-else-if="empty" class="state-block" :class="{ compact }">
    <el-empty :description="emptyText">
      <slot name="empty-action" />
    </el-empty>
  </div>
  <slot v-else />
</template>

<style scoped>
.state-block {
  padding: 28px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
}

.state-block.compact {
  padding: 16px;
}

@media (max-width: 640px) {
  .state-block {
    padding: 18px;
  }
}
</style>
