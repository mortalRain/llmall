<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

onMounted(() => {
  // 刷新页面后用本地令牌同步一次最新用户信息；401 由 http 拦截器统一处理
  if (auth.accessToken) {
    auth.fetchMe().catch(() => {
      // 页面级错误无需打断首屏，http 拦截器会在会话彻底失效时跳转登录
    })
  }
})
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="page-fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>
