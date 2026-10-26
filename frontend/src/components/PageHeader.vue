<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    eyebrow?: string
  }>(),
  {
    subtitle: '',
    eyebrow: '',
  },
)
</script>

<template>
  <header class="page-header">
    <div class="page-header-copy">
      <span v-if="eyebrow" class="eyebrow">{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}

.page-header-copy {
  min-width: 0;
}

h1 {
  margin: 4px 0 0;
  color: var(--ink);
  font-size: clamp(26px, 3.2vw, 38px);
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -0.035em;
}

p {
  max-width: 680px;
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.page-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

@media (max-width: 640px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
    margin-bottom: 18px;
  }

  h1 {
    font-size: 27px;
  }

  p {
    font-size: 13px;
  }

  .page-header-actions {
    width: 100%;
  }
}
</style>
