<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight,
  Discount,
  Refresh,
  ShoppingCart,
  Star,
  SwitchButton,
  Trophy,
  Wallet,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import VoucherCard from '@/components/VoucherCard.vue'
import PayPasswordDialog from '@/components/PayPasswordDialog.vue'
import { voucherApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { UserVoucher } from '@/api/types'
import { formatMoney } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(true)
const errorMessage = ref('')
const loggingOut = ref(false)
const payPasswordVisible = ref(false)

async function passwordChanged(): Promise<void> {
  auth.reset()
  ElMessage.success('支付口令已修改，请重新登录')
  await router.replace('/login')
}

const vouchers = ref<UserVoucher[]>([])
const voucherLoading = ref(true)
const voucherError = ref('')
const activeVoucherStatus = ref('all')

const filters = [
  { label: '全部', value: 'all' },
  { label: '未使用', value: '1' },
  { label: '已使用', value: '2' },
  { label: '已过期', value: '3' },
]

const filteredVouchers = computed(() => {
  if (activeVoucherStatus.value === 'all') return vouchers.value
  return vouchers.value.filter(
    (voucher) => String(voucher.status) === activeVoucherStatus.value,
  )
})

const unusedCount = computed(
  () => vouchers.value.filter((voucher) => Number(voucher.status) === 1).length,
)

const filterCounts = computed<Record<string, number>>(() => {
  const counts: Record<string, number> = { all: vouchers.value.length }
  vouchers.value.forEach((voucher) => {
    const key = String(voucher.status)
    counts[key] = (counts[key] || 0) + 1
  })
  return counts
})

async function loadProfile(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    await auth.fetchMe()
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '账户信息加载失败')
  } finally {
    loading.value = false
  }
}

async function loadVouchers(): Promise<void> {
  voucherLoading.value = true
  voucherError.value = ''
  try {
    vouchers.value = await voucherApi.my()
  } catch (error) {
    voucherError.value = getErrorMessage(error, '优惠券加载失败')
    vouchers.value = []
  } finally {
    voucherLoading.value = false
  }
}

async function logout(): Promise<void> {
  if (loggingOut.value) return
  try {
    await ElMessageBox.confirm('退出后将清除本地登录状态，确认退出当前账号？', '退出登录', {
      confirmButtonText: '退出登录',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  loggingOut.value = true
  try {
    await auth.logout()
    ElMessage.success('已退出登录')
    await router.replace('/login')
  } finally {
    loggingOut.value = false
  }
}

function goShops(): void {
  void router.push('/shops')
}

onMounted(() => {
  void loadProfile()
  void loadVouchers()
})
</script>

<template>
  <div class="profile-page">
    <PageHeader
      eyebrow="MY ACCOUNT"
      title="我的"
      subtitle="账户余额、积分与优惠券，都在这里统一管理。"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="loadProfile">刷新资料</el-button>
      </template>
    </PageHeader>

    <StateBlock :loading="loading" :error="errorMessage" @retry="loadProfile">
      <div class="profile-hero">
        <div class="profile-identity">
          <el-avatar :size="68" class="profile-avatar">{{ auth.avatarText }}</el-avatar>
          <div class="identity-text">
            <div class="identity-name">
              <h2>{{ auth.displayName }}</h2>
              <el-tag size="small" effect="light" type="primary">
                Lv.{{ auth.user?.level ?? '—' }}
              </el-tag>
            </div>
            <p>
              <span>{{ auth.user?.username || '—' }}</span>
              <span class="dot-sep"></span>
              <span class="mono">用户 ID：{{ auth.user?.userId || '—' }}</span>
            </p>
          </div>
        </div>

        <div class="profile-stats">
          <div>
            <span>账户余额（元）</span>
            <b>{{ formatMoney(auth.balance) }}</b>
          </div>
          <div>
            <span>我的积分</span>
            <b>{{ auth.credits }}</b>
          </div>
          <div>
            <span>未使用优惠券</span>
            <b>{{ unusedCount }}</b>
          </div>
        </div>
      </div>

      <div class="profile-shortcuts">
        <router-link to="/favorites" class="shortcut-card">
          <span class="shortcut-icon favorite"><el-icon><Star /></el-icon></span>
          <span class="shortcut-text">
            <b>我的收藏</b>
            <small>查看收藏的门店，随时取消</small>
          </span>
          <el-icon class="shortcut-arrow"><ArrowRight /></el-icon>
        </router-link>
        <router-link to="/cart" class="shortcut-card">
          <span class="shortcut-icon cart"><el-icon><ShoppingCart /></el-icon></span>
          <span class="shortcut-text">
            <b>购物车</b>
            <small>修改数量、选择商品去结算</small>
          </span>
          <el-icon class="shortcut-arrow"><ArrowRight /></el-icon>
        </router-link>
      </div>

      <div class="profile-grid">
        <aside class="account-panel">
          <div class="account-panel-head">
            <h3>账户信息</h3>
            <span class="panel-icon"><el-icon><Wallet /></el-icon></span>
          </div>

          <div class="account-rows">
            <div>
              <span>昵称</span>
              <b>{{ auth.user?.nickName || '—' }}</b>
            </div>
            <div>
              <span>登录账号</span>
              <b>{{ auth.user?.username || '—' }}</b>
            </div>
            <div>
              <span>用户 ID</span>
              <b class="mono">{{ auth.user?.userId || '—' }}</b>
            </div>
            <div>
              <span>会员等级</span>
              <b>
                <el-icon class="row-icon"><Trophy /></el-icon>
                Lv.{{ auth.user?.level ?? '—' }}
              </b>
            </div>
            <div>
              <span>账户余额</span>
              <b class="balance">{{ formatMoney(auth.balance) }}</b>
            </div>
            <div>
              <span>积分</span>
              <b>{{ auth.credits }}</b>
            </div>
          </div>

          <div class="account-actions">
            <el-button @click="payPasswordVisible = true">修改支付口令</el-button>
            <el-button :icon="SwitchButton" :loading="loggingOut" @click="logout">
              退出登录
            </el-button>
          </div>

          <div class="account-tip">
            <el-icon><Discount /></el-icon>
            <p>优惠券仅限对应门店使用，下单时自动出现在可用券列表中。</p>
          </div>
        </aside>

        <section class="voucher-panel">
          <div class="voucher-panel-head">
            <div>
              <h3>我的优惠券</h3>
              <p>共 {{ vouchers.length }} 张，{{ unusedCount }} 张可用</p>
            </div>
            <router-link to="/shops" class="shop-link">
              去领券
              <el-icon><ArrowRight /></el-icon>
            </router-link>
          </div>

          <div class="voucher-filters">
            <button
              v-for="filter in filters"
              :key="filter.value"
              type="button"
              class="filter-chip"
              :class="{ active: activeVoucherStatus === filter.value }"
              @click="activeVoucherStatus = filter.value"
            >
              {{ filter.label }}
              <span>{{ filterCounts[filter.value] || 0 }}</span>
            </button>
          </div>

          <div v-if="voucherLoading" class="voucher-loading">
            <el-skeleton v-for="index in 2" :key="index" :rows="3" animated />
          </div>

          <div v-else-if="voucherError" class="voucher-error">
            <el-result icon="error" title="优惠券加载失败" :sub-title="voucherError">
              <template #extra>
                <el-button type="primary" :icon="Refresh" @click="loadVouchers">重新加载</el-button>
              </template>
            </el-result>
          </div>

          <div v-else-if="!filteredVouchers.length" class="voucher-empty">
            <el-empty
              :description="
                activeVoucherStatus === 'all' ? '还没有优惠券' : '这个状态下没有优惠券'
              "
              :image-size="96"
            >
              <el-button type="primary" @click="goShops">去门店领券</el-button>
            </el-empty>
          </div>

          <div v-else class="voucher-list">
            <VoucherCard
              v-for="voucher in filteredVouchers"
              :key="voucher.id"
              :voucher="voucher"
              mode="mine"
            />
          </div>
        </section>
      </div>
    </StateBlock>
    <PayPasswordDialog v-model="payPasswordVisible" @changed="passwordChanged" />
  </div>
</template>

<style scoped>
.profile-page {
  width: 100%;
}

.profile-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 26px;
  padding: 26px 28px;
  overflow: hidden;
  border-radius: var(--radius-lg);
  background:
    radial-gradient(circle at 88% 0, rgba(255, 255, 255, 0.55), transparent 42%),
    linear-gradient(135deg, #eff6ff 0%, #dbeafe 48%, #bfdbfe 100%);
  border: 1px solid var(--brand-100);
  box-shadow: var(--shadow-sm);
}

.profile-identity {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.profile-avatar {
  background: linear-gradient(135deg, var(--brand-600), #93c5fd);
  color: #fff;
  font-size: 26px;
  font-weight: 850;
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.22);
  flex: none;
}

.identity-text {
  min-width: 0;
}

.identity-name {
  display: flex;
  align-items: center;
  gap: 9px;
}

.identity-name h2 {
  margin: 0;
  overflow: hidden;
  color: #10325c;
  font-size: 27px;
  font-weight: 850;
  letter-spacing: -0.035em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-text p {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  margin: 5px 0 0;
  color: #54769e;
  font-size: 12px;
}

.profile-stats {
  display: flex;
  gap: 10px;
  flex: none;
}

.profile-stats > div {
  display: flex;
  flex-direction: column;
  min-width: 124px;
  padding: 12px 15px;
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.58);
  backdrop-filter: blur(10px);
}

.profile-stats span {
  color: #617f9f;
  font-size: 10.5px;
  white-space: nowrap;
}

.profile-stats b {
  margin-top: 2px;
  color: #12365f;
  font-size: 21px;
  font-weight: 850;
  line-height: 1.25;
  letter-spacing: -0.03em;
  white-space: nowrap;
}

.profile-shortcuts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 16px;
}

.shortcut-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
  transition:
    border-color 0.16s ease,
    transform 0.16s ease,
    box-shadow 0.16s ease;
}

.shortcut-card:hover {
  transform: translateY(-2px);
  border-color: var(--brand-200);
  box-shadow: 0 14px 28px rgba(37, 99, 235, 0.1);
}

.shortcut-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 12px;
  font-size: 19px;
}

.shortcut-icon.favorite {
  background: #fff7ed;
  color: #d97706;
}

.shortcut-icon.cart {
  background: var(--brand-50);
  color: var(--brand-600);
}

.shortcut-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.shortcut-text b {
  color: var(--ink);
  font-size: 13.5px;
  font-weight: 750;
}

.shortcut-text small {
  overflow: hidden;
  color: var(--faint);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shortcut-arrow {
  flex: none;
  color: var(--faint);
}

.profile-grid {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  align-items: start;
  gap: 20px;
  margin-top: 20px;
}

.account-panel,
.voucher-panel {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.account-panel {
  padding: 20px;
}

.account-panel-head,
.voucher-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.account-panel-head h3,
.voucher-panel-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 750;
}

.panel-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: var(--brand-50);
  color: var(--brand-500);
}

.account-rows {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-top: 14px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--line);
}

.account-rows > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 12px;
  background: var(--surface);
  font-size: 11.5px;
}

.account-rows span {
  color: var(--muted);
  white-space: nowrap;
}

.account-rows b {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  overflow: hidden;
  color: var(--ink-2);
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-rows .balance {
  color: var(--brand-600);
  font-size: 13px;
  font-weight: 800;
}

.row-icon {
  color: var(--gold);
  font-size: 13px;
}

.account-actions {
  margin-top: 14px;
}

.account-actions :deep(.el-button) {
  width: 100%;
}

.account-tip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 11px 12px;
  border-radius: 10px;
  background: var(--brand-50);
  color: var(--brand-500);
}

.account-tip .el-icon {
  margin-top: 2px;
  flex: none;
}

.account-tip p {
  margin: 0;
  color: var(--muted);
  font-size: 10.5px;
  line-height: 1.6;
}

.voucher-panel {
  padding: 20px;
}

.voucher-panel-head p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 11.5px;
}

.shop-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex: none;
  color: var(--brand-600);
  font-size: 12px;
  font-weight: 600;
}

.voucher-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 15px 0;
  padding-bottom: 14px;
  border-bottom: 1px solid #f0f6ff;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-soft);
  color: var(--muted);
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  transition:
    color 0.16s ease,
    border-color 0.16s ease,
    background-color 0.16s ease;
}

.filter-chip span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.15);
  color: var(--faint);
  font-size: 9.5px;
}

.filter-chip:hover {
  border-color: var(--brand-200);
  color: var(--brand-600);
}

.filter-chip.active {
  border-color: var(--brand-400);
  background: var(--brand-50);
  color: var(--brand-700);
}

.filter-chip.active span {
  background: var(--brand-500);
  color: #fff;
}

.voucher-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.voucher-loading {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.voucher-empty {
  padding: 10px 0;
}

.voucher-error :deep(.el-result) {
  padding: 18px 8px;
}

@media (max-width: 1024px) {
  .profile-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .profile-stats {
    width: 100%;
  }

  .profile-stats > div {
    flex: 1;
    min-width: 0;
  }

  .profile-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .account-panel {
    order: 2;
  }

  .voucher-panel {
    order: 1;
  }
}

@media (max-width: 640px) {
  .profile-shortcuts {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }

  .profile-hero {
    padding: 20px 17px;
  }

  .profile-avatar {
    width: 56px !important;
    height: 56px !important;
    font-size: 22px;
  }

  .identity-name h2 {
    font-size: 22px;
  }

  .profile-stats {
    gap: 6px;
  }

  .profile-stats > div {
    padding: 9px 10px;
  }

  .profile-stats span {
    font-size: 9.5px;
  }

  .profile-stats b {
    font-size: 16px;
  }

  .profile-grid {
    margin-top: 14px;
    gap: 14px;
  }

  .account-panel,
  .voucher-panel {
    padding: 15px;
  }

  .voucher-panel-head {
    align-items: center;
  }
}
</style>
