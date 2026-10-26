<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ArrowRight,
  Compass,
  Goods,
  Management,
  Shop,
  ShoppingCart,
  Stamp,
  Star,
  SwitchButton,
  Tickets,
  User,
  Wallet,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { shopApi } from '@/api/modules'
import type { MyShopStaff } from '@/api/types'
import { formatMoney } from '@/utils/format'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/**
 * 员工模式入口：
 * - 任意角色（OWNER/MANAGER/STAFF）都显示"核销台"；
 * - 只有 OWNER/MANAGER 显示"员工管理"。
 *
 * 【注意】隐藏入口只是 UX：即便手动敲 /staff/verify 或 /staff/manage，页面会再校验一次身份；
 * 即便伪造身份，服务端仍会按 (shopId, userId, role) 判权（核销 403、管理 403）。
 * 接口失败时按空集合处理（fail-closed），不展示入口。
 */
const staffShops = ref<MyShopStaff[]>([])

const staffShopIds = computed(() => staffShops.value.map((item) => String(item.shopId)))
const managerShopIds = computed(() =>
  staffShops.value
    .filter((item) => item.role === 'OWNER' || item.role === 'MANAGER')
    .map((item) => String(item.shopId)),
)

const navItems = computed(() => {
  const items = [
    { to: '/feed', label: '动态', icon: Compass },
    { to: '/mall', label: '商城', icon: Goods },
    { to: '/shops', label: '门店', icon: Shop },
    { to: '/cart', label: '购物车', icon: ShoppingCart },
    { to: '/favorites', label: '我的收藏', icon: Star },
    { to: '/orders', label: '订单', icon: Tickets },
    { to: '/profile', label: '我的', icon: User },
  ]
  if (staffShopIds.value.length) {
    items.push({ to: '/staff/verify', label: '核销台', icon: Stamp })
  }
  if (managerShopIds.value.length) {
    items.push({ to: '/staff/manage', label: '员工管理', icon: Management })
  }
  return items
})

async function loadStaffShops(): Promise<void> {
  try {
    const shops = (await shopApi.myStaffShops()) as unknown as Array<MyShopStaff | string>
    // 兼容过渡态：老后端返回 ["10"]，新后端返回 [{shopId, role}]
    staffShops.value = Array.isArray(shops)
      ? shops.map((item) =>
          typeof item === 'string' ? { shopId: item, role: 'STAFF' as const } : item,
        )
      : []
  } catch {
    staffShops.value = []
  }
}

onMounted(() => {
  void loadStaffShops()
})

const currentTitle = computed(() => {
  return typeof route.meta.title === 'string' ? route.meta.title : '同城生活'
})

const balanceText = computed(() => formatMoney(auth.balance))
const creditText = computed(() => String(auth.credits))

function isActive(path: string): boolean {
  if (path === '/feed') return route.path === '/feed'
  // 商品详情属于商城链路，导航高亮留在"商城"
  if (path === '/mall') return route.path === '/mall' || route.path.startsWith('/item/')
  return route.path.startsWith(path)
}

async function handleCommand(command: string): Promise<void> {
  if (command === 'profile') {
    await router.push('/profile')
    return
  }
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('退出后将清除本地登录状态，确认退出？', '退出登录', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    await auth.logout()
    ElMessage.success('已退出登录')
    await router.replace('/login')
  }
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <router-link class="brand" to="/feed">
        <span class="brand-mark">L</span>
        <span class="brand-text">
          <b>LLMall</b>
          <small>同城生活</small>
        </span>
      </router-link>

      <nav class="side-nav" aria-label="主导航">
        <router-link
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="side-link"
          :class="{ active: isActive(item.to) }"
        >
          <el-icon :size="18"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
          <span class="active-dot"></span>
        </router-link>
      </nav>

      <div class="side-balance">
        <div class="balance-label">
          <el-icon><Wallet /></el-icon>
          账户余额（元）
        </div>
        <div class="balance-value">{{ balanceText }}</div>
        <router-link class="balance-link" to="/profile">
          查看我的账户
          <el-icon><ArrowRight /></el-icon>
        </router-link>
      </div>

      <div class="side-user">
        <el-avatar :size="38" class="side-avatar">{{ auth.avatarText }}</el-avatar>
        <div class="side-user-text">
          <b>{{ auth.displayName }}</b>
          <span>{{ creditText }} 积分</span>
        </div>
        <el-tooltip content="退出登录" placement="top">
          <el-button text circle class="side-logout" @click="handleCommand('logout')">
            <el-icon><SwitchButton /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="topbar-left">
          <div class="mobile-brand">
            <span class="brand-mark small">L</span>
            <b>{{ currentTitle }}</b>
          </div>
          <div class="breadcrumb">
            <span>LLMall</span>
            <i>/</i>
            <b>{{ currentTitle }}</b>
          </div>
        </div>

        <div class="topbar-actions">
          <router-link class="balance-pill" to="/profile">
            <el-icon><Wallet /></el-icon>
            <span class="balance-pill-label">余额</span>
            <b>{{ balanceText }}</b>
          </router-link>

          <el-dropdown trigger="click" @command="handleCommand">
            <button class="user-chip" type="button">
              <el-avatar :size="32" class="chip-avatar">{{ auth.avatarText }}</el-avatar>
              <span class="user-chip-name">{{ auth.displayName }}</span>
              <el-icon class="user-chip-arrow"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon><User /></el-icon>
                  个人中心
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="page">
        <router-view v-slot="{ Component, route: currentRoute }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" :key="currentRoute.path" />
          </transition>
        </router-view>
      </main>

      <footer class="footer">
        <span>LLMall · 同城生活</span>
        <span>Vue 3 · TypeScript · Element Plus</span>
      </footer>
    </div>

    <nav class="mobile-nav" aria-label="移动端导航">
      <router-link
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="mobile-link"
        :class="{ active: isActive(item.to) }"
      >
        <el-icon :size="20"><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </router-link>
    </nav>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  width: 248px;
  padding: 24px 18px 20px;
  background: rgba(255, 255, 255, 0.92);
  border-right: 1px solid var(--line);
  backdrop-filter: blur(16px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 0 8px;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.1;
}

.brand-text b {
  color: var(--ink);
  font-size: 19px;
  font-weight: 850;
  letter-spacing: -0.03em;
}

.brand-text small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
  letter-spacing: 0.12em;
}

.side-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 32px;
}

.side-link {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  height: 44px;
  padding: 0 13px;
  border-radius: 11px;
  color: var(--muted);
  font-size: 14px;
  font-weight: 600;
  transition:
    color 0.16s ease,
    background-color 0.16s ease;
}

.side-link:hover {
  color: var(--brand-600);
  background: var(--brand-50);
}

.side-link.active {
  color: var(--brand-700);
  background: linear-gradient(90deg, #eaf3ff, #f5faff);
  font-weight: 750;
}

.side-link.active::before {
  content: '';
  position: absolute;
  left: 0;
  width: 3px;
  height: 20px;
  border-radius: 0 3px 3px 0;
  background: var(--brand-500);
}

.active-dot {
  width: 6px;
  height: 6px;
  margin-left: auto;
  border-radius: 50%;
  background: transparent;
}

.side-link.active .active-dot {
  background: var(--brand-500);
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.1);
}

.side-balance {
  margin-top: auto;
  padding: 16px;
  border-radius: 16px;
  background:
    radial-gradient(circle at 100% 0, rgba(147, 197, 253, 0.55), transparent 55%),
    linear-gradient(135deg, #eff6ff, #dbeafe);
  border: 1px solid var(--brand-100);
}

.balance-label {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--brand-600);
  font-size: 11.5px;
  font-weight: 600;
}

.balance-value {
  margin-top: 5px;
  color: var(--brand-700);
  font-size: 24px;
  font-weight: 850;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.balance-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-top: 8px;
  color: var(--brand-600);
  font-size: 11.5px;
  font-weight: 600;
}

.side-user {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 16px;
  padding: 9px 6px 0;
  border-top: 1px solid #f0f6ff;
}

.side-avatar,
.chip-avatar {
  background: linear-gradient(135deg, var(--brand-500), #93c5fd);
  color: #fff;
  font-weight: 800;
  flex: none;
}

.side-user-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.side-user-text b {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side-user-text span {
  color: var(--faint);
  font-size: 11.5px;
}

.side-logout {
  color: var(--faint);
}

.main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  margin-left: 248px;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 15;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 68px;
  padding: 0 36px;
  background: rgba(245, 249, 255, 0.82);
  border-bottom: 1px solid rgba(226, 237, 255, 0.8);
  backdrop-filter: blur(14px);
}

.topbar-left {
  min-width: 0;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--faint);
  font-size: 12.5px;
}

.breadcrumb i {
  font-style: normal;
  color: var(--line-strong);
}

.breadcrumb b {
  overflow: hidden;
  color: var(--ink-2);
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mobile-brand {
  display: none;
  align-items: center;
  gap: 9px;
}

.mobile-brand .brand-mark.small {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  font-size: 17px;
}

.mobile-brand b {
  font-size: 15px;
  font-weight: 750;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.balance-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 36px;
  padding: 0 12px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid var(--line);
  color: var(--brand-600);
  font-size: 12px;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.05);
}

.balance-pill b {
  color: var(--brand-700);
  font-size: 13px;
  font-weight: 800;
}

.balance-pill-label {
  color: var(--muted);
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 40px;
  padding: 0 8px 0 5px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  transition: background-color 0.16s ease;
}

.user-chip:hover {
  background: #fff;
  border-color: var(--line);
}

.user-chip-name {
  max-width: 100px;
  overflow: hidden;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-chip-arrow {
  color: var(--faint);
  font-size: 12px;
}

.page {
  flex: 1;
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: 30px 36px 24px;
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
  padding: 16px 36px 24px;
  color: var(--faint);
  font-size: 11.5px;
}

.mobile-nav {
  display: none;
}

@media (max-width: 1024px) {
  .sidebar {
    width: 218px;
  }

  .main {
    margin-left: 218px;
  }

  .topbar,
  .page,
  .footer {
    padding-left: 24px;
    padding-right: 24px;
  }
}

@media (max-width: 860px) {
  .sidebar {
    display: none;
  }

  .main {
    margin-left: 0;
    padding-bottom: calc(68px + env(safe-area-inset-bottom));
  }

  .topbar {
    height: 62px;
    padding: 0 16px;
    background: rgba(255, 255, 255, 0.9);
  }

  .breadcrumb {
    display: none;
  }

  .mobile-brand {
    display: flex;
  }

  .page {
    padding: 20px 16px 18px;
  }

  .footer {
    padding: 8px 16px 18px;
    font-size: 10.5px;
  }

  .mobile-nav {
    position: fixed;
    inset: auto 0 0 0;
    z-index: 30;
    display: flex;
    height: calc(62px + env(safe-area-inset-bottom));
    padding: 5px 8px env(safe-area-inset-bottom);
    background: rgba(255, 255, 255, 0.96);
    border-top: 1px solid var(--line);
    backdrop-filter: blur(16px);
  }

  .mobile-link {
    display: flex;
    flex: 1;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 1px;
    border-radius: 10px;
    color: var(--faint);
    font-size: 10.5px;
    font-weight: 600;
  }

  .mobile-link.active {
    color: var(--brand-600);
    background: var(--brand-50);
  }
}

@media (max-width: 480px) {
  .balance-pill {
    height: 32px;
    padding: 0 9px;
  }

  .balance-pill-label {
    display: none;
  }

  .user-chip-name,
  .user-chip-arrow {
    display: none;
  }

  .user-chip {
    padding: 0;
    height: 34px;
  }

  .footer {
    display: none;
  }
}
</style>
