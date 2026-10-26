import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 网关默认跑在 8080，前端统一以 /api 作为同源前缀。
 * dev / preview 都把 /api 去掉后转发到网关，避免浏览器跨域。
 */
const apiTarget = process.env.LLMALL_API_TARGET || 'http://localhost:8080'

const proxy = {
  '/api': {
    target: apiTarget,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api/, ''),
  },
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    proxy,
  },
  preview: {
    host: '0.0.0.0',
    port: 4173,
    strictPort: true,
    proxy,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1600,
    rollupOptions: {
      output: {
        // Element Plus 全量引入后体积集中在两个 vendor chunk，单独拆出来便于缓存
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('element-plus')) return 'element-plus'
          if (id.includes('@element-plus/icons-vue')) return 'element-icons'
          if (id.includes('vue') || id.includes('pinia') || id.includes('vue-router')) return 'vue-vendor'
          return 'vendor'
        },
      },
    },
  },
})
