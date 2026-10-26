<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight, Refresh, Tickets, View } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { orderApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import type { Order } from '@/api/types'
import {
  formatCount,
  formatDateTime,
  formatMoney,
  initialOf,
  orderStatusMeta,
  paymentTypeText,
} from '@/utils/format'

const PAGE_SIZE = 5

const route = useRoute()
const router = useRouter()

const initialStatus =
  typeof route.query.status === 'string' && ['1', '2', '7', '5'].includes(route.query.status)
    ? route.query.status
    : 'all'

const activeStatus = ref(initialStatus)
const pageNo = ref(1)
const orders = ref<Order[]>([])
const total = ref(0)
const loading = ref(true)
const errorMessage = ref('')
const cancelingId = ref('')

const detailVisible = ref(false)
const detail = ref<Order | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

const verifyVisible = ref(false)
const verifying = ref(false)
const verifyTarget = ref<Order | null>(null)
const verifyForm = reactive({
  verifyCode: '',
  shopId: '',
})

const tabs = [
  { label: '全部', value: 'all' },
  { label: '待付款', value: '1' },
  { label: '待使用', value: '2' },
  { label: '已核销', value: '7' },
  { label: '已关闭', value: '5' },
]

const detailStatus = computed(() => orderStatusMeta(Number(detail.value?.status || 0)))
const detailActive = computed(() => {
  const status = Number(detail.value?.status || 0)
  if (status === 1) return 1
  if (status === 2) return 2
  if (status === 7) return 3
  return 1
})

async function loadOrders(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const status = activeStatus.value === 'all' ? undefined : Number(activeStatus.value)
    const result = await orderApi.page({
      pageNo: pageNo.value,
      pageSize: PAGE_SIZE,
      status,
    })
    orders.value = Array.isArray(result.list) ? result.list : []
    total.value = Number(result.total || 0)
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '订单加载失败')
    orders.value = []
  } finally {
    loading.value = false
  }
}

function changeStatus(name: string | number): void {
  activeStatus.value = String(name)
  pageNo.value = 1
  void router.replace({
    query: activeStatus.value === 'all' ? {} : { status: activeStatus.value },
  })
  void loadOrders()
}

function changePage(nextPage: number): void {
  pageNo.value = nextPage
  void loadOrders()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function openDetail(order: Order): Promise<void> {
  detailVisible.value = true
  detail.value = order
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await orderApi.detail(order.id)
  } catch (error) {
    detailError.value = getErrorMessage(error, '订单详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function cancelOrder(order: Order): Promise<void> {
  if (cancelingId.value) return
  try {
    await ElMessageBox.confirm(
      `确认取消订单 ${order.id}？未支付订单取消后会自动回滚库存与优惠券。`,
      '取消订单',
      {
        confirmButtonText: '确认取消',
        cancelButtonText: '再想想',
        type: 'warning',
      },
    )
  } catch {
    return
  }

  cancelingId.value = order.id
  try {
    await orderApi.cancel(order.id)
    ElMessage.success('订单已取消')
    await loadOrders()
    if (detailVisible.value && detail.value?.id === order.id) {
      await openDetail(order)
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '取消订单失败'))
  } finally {
    cancelingId.value = ''
  }
}

function openVerify(order: Order): void {
  verifyTarget.value = order
  verifyForm.verifyCode = order.verifyCode || ''
  verifyForm.shopId = order.shopId || ''
  verifyVisible.value = true
}

async function submitVerify(): Promise<void> {
  if (!verifyForm.verifyCode.trim()) {
    ElMessage.warning('请输入核销码')
    return
  }
  if (!verifyForm.shopId.trim()) {
    ElMessage.warning('请输入核销门店 ID')
    return
  }

  verifying.value = true
  try {
    await orderApi.verify({
      verifyCode: verifyForm.verifyCode.trim().toUpperCase(),
      shopId: verifyForm.shopId.trim(),
    })
    ElMessage.success('核销成功')
    verifyVisible.value = false
    await loadOrders()
    if (detailVisible.value && detail.value) {
      await openDetail(detail.value)
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '核销失败'))
  } finally {
    verifying.value = false
  }
}

function goShops(): void {
  void router.push('/shops')
}

onMounted(() => {
  void loadOrders()
})
</script>

<template>
  <div class="orders-page">
    <PageHeader
      eyebrow="MY ORDERS"
      title="我的订单"
      subtitle="查看订单状态与核销码，未支付订单可取消，已支付订单可到店核销。"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="loadOrders">刷新订单</el-button>
      </template>
    </PageHeader>

    <div class="order-tabs">
      <el-tabs v-model="activeStatus" @tab-change="changeStatus">
        <el-tab-pane v-for="tab in tabs" :key="tab.value" :label="tab.label" :name="tab.value" />
      </el-tabs>
    </div>

    <div v-if="loading" class="order-skeletons">
      <div v-for="index in 3" :key="index" class="skeleton-order">
        <div class="skeleton-order-head">
          <el-skeleton-item variant="text" style="width: 32%" />
          <el-skeleton-item variant="text" style="width: 70px" />
        </div>
        <el-skeleton :rows="2" animated />
      </div>
    </div>

    <div v-else-if="errorMessage" class="error-card">
      <div class="error-icon">!</div>
      <h3>订单加载失败</h3>
      <p>{{ errorMessage }}</p>
      <el-button type="primary" :icon="Refresh" @click="loadOrders">重新加载</el-button>
    </div>

    <div v-else-if="!orders.length" class="empty-orders">
      <div class="empty-icon"><el-icon><Tickets /></el-icon></div>
      <h3>{{ activeStatus === 'all' ? '还没有订单' : '这个状态下没有订单' }}</h3>
      <p>去门店挑一份套餐，使用余额支付后就能在订单里看到核销码。</p>
      <el-button type="primary" @click="goShops">
        去逛门店
        <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>

    <template v-else>
      <div class="order-list">
        <article v-for="order in orders" :key="order.id" class="order-card">
          <header class="order-head">
            <div class="order-id">
              <span>订单号</span>
              <b class="mono">{{ order.id }}</b>
              <small>{{ formatDateTime(order.createTime) }}</small>
            </div>
            <el-tag :type="orderStatusMeta(Number(order.status)).type" effect="light">
              {{ orderStatusMeta(Number(order.status)).text }}
            </el-tag>
          </header>

          <div class="order-body">
            <div class="order-thumbs">
              <div
                v-for="(detailItem, index) in (order.details || []).slice(0, 3)"
                :key="detailItem.id || index"
                class="order-thumb"
              >
                <img
                  v-if="detailItem.image"
                  :src="detailItem.image"
                  :alt="detailItem.name"
                  @error="($event.target as HTMLImageElement).style.display = 'none'"
                />
                <span>{{ initialOf(detailItem.name) }}</span>
              </div>
              <div v-if="!(order.details || []).length" class="order-thumb">
                <span>L</span>
              </div>
            </div>

            <div class="order-items">
              <h3>
                {{ order.details?.[0]?.name || '门店订单' }}
                <span v-if="(order.details || []).length > 1">
                  等 {{ order.details.length }} 件
                </span>
              </h3>
              <p>
                {{ (order.details || []).map((detailItem) => `${detailItem.name}×${detailItem.num}`).join('、') || '暂无商品明细' }}
              </p>
              <div class="order-meta">
                <span>{{ paymentTypeText(order.paymentType) }}</span>
                <span v-if="order.shopId">门店 #{{ order.shopId }}</span>
                <span>共 {{ formatCount((order.details || []).reduce((sum, detailItem) => sum + detailItem.num, 0)) }} 件</span>
              </div>
            </div>

            <div class="order-total">
              <span>{{ Number(order.status) === 1 ? '应付金额' : '实付金额' }}</span>
              <b>{{ formatMoney(order.totalFee) }}</b>
            </div>
          </div>

          <div v-if="order.verifyCode && Number(order.status) !== 5" class="verify-strip">
            <span>核销码</span>
            <b class="mono">{{ order.verifyCode }}</b>
            <small>到店出示，由门店核销</small>
          </div>

          <footer class="order-foot">
            <span class="status-hint">{{ orderStatusMeta(Number(order.status)).hint }}</span>
            <div class="order-actions">
              <el-button size="small" :icon="View" @click="openDetail(order)">查看详情</el-button>
              <el-button
                v-if="Number(order.status) === 1"
                size="small"
                type="danger"
                plain
                :loading="cancelingId === order.id"
                @click="cancelOrder(order)"
              >
                取消订单
              </el-button>
              <el-button
                v-if="Number(order.status) === 2"
                size="small"
                type="primary"
                @click="openVerify(order)"
              >
                去核销
              </el-button>
            </div>
          </footer>
        </article>
      </div>

      <div v-if="total > PAGE_SIZE" class="pagination-wrap">
        <el-pagination
          background
          layout="prev, pager, next"
          :current-page="pageNo"
          :page-size="PAGE_SIZE"
          :total="total"
          @current-change="changePage"
        />
      </div>
    </template>

    <el-drawer v-model="detailVisible" title="订单详情" size="520px">
      <div v-if="detailLoading" class="drawer-state">
        <el-skeleton :rows="7" animated />
      </div>
      <div v-else-if="detailError" class="drawer-state">
        <el-result icon="error" title="详情加载失败" :sub-title="detailError" />
      </div>
      <div v-else-if="detail" class="detail-body">
        <div class="detail-head">
          <div>
            <span>订单号</span>
            <b class="mono">{{ detail.id }}</b>
          </div>
          <el-tag :type="detailStatus.type" effect="light">{{ detailStatus.text }}</el-tag>
        </div>

        <el-steps :active="detailActive" finish-status="success" align-center class="order-steps">
          <el-step title="提交订单" />
          <el-step title="完成支付" />
          <el-step title="到店核销" />
        </el-steps>

        <div v-if="detail.verifyCode" class="big-verify">
          <span>核销码</span>
          <b>{{ detail.verifyCode }}</b>
          <small>{{ detailStatus.hint }}</small>
        </div>

        <div class="detail-grid">
          <div>
            <span>订单金额</span>
            <b>{{ formatMoney(detail.totalFee) }}</b>
          </div>
          <div>
            <span>支付方式</span>
            <b>{{ paymentTypeText(detail.paymentType) }}</b>
          </div>
          <div>
            <span>下单时间</span>
            <b>{{ formatDateTime(detail.createTime) }}</b>
          </div>
          <div>
            <span>支付时间</span>
            <b>{{ formatDateTime(detail.payTime) }}</b>
          </div>
          <div>
            <span>门店</span>
            <b>{{ detail.shopId ? `#${detail.shopId}` : '—' }}</b>
          </div>
          <div>
            <span>优惠券</span>
            <b>{{ detail.voucherId ? '已使用门店券' : '未使用' }}</b>
          </div>
        </div>

        <div class="detail-goods">
          <h4>商品明细</h4>
          <div v-for="detailItem in detail.details || []" :key="detailItem.id" class="detail-goods-row">
            <div class="detail-goods-thumb">
              <img v-if="detailItem.image" :src="detailItem.image" :alt="detailItem.name" />
              <span v-else>{{ initialOf(detailItem.name) }}</span>
            </div>
            <div class="detail-goods-info">
              <b>{{ detailItem.name }}</b>
              <span>{{ detailItem.spec || '到店核销' }}</span>
            </div>
            <div class="detail-goods-price">
              <b>{{ formatMoney(detailItem.price * detailItem.num) }}</b>
              <span>{{ formatMoney(detailItem.price) }} × {{ detailItem.num }}</span>
            </div>
          </div>
        </div>

        <div class="detail-actions">
          <el-button
            v-if="Number(detail.status) === 1"
            type="danger"
            plain
            :loading="cancelingId === detail.id"
            @click="cancelOrder(detail)"
          >
            取消订单
          </el-button>
          <el-button
            v-if="Number(detail.status) === 2"
            type="primary"
            @click="openVerify(detail)"
          >
            去核销
          </el-button>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="verifyVisible" title="订单核销" width="480px" append-to-body>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="核销是门店侧动作"
        description="核销码由顾客到店出示。演示环境可在此模拟门店核销，核销门店必须与订单门店一致。"
      />
      <el-form label-position="top" class="verify-form">
        <el-form-item label="核销码">
          <el-input
            v-model="verifyForm.verifyCode"
            placeholder="请输入订单核销码"
            maxlength="16"
          />
        </el-form-item>
        <el-form-item label="核销门店 ID">
          <el-input v-model="verifyForm.shopId" placeholder="请输入门店 id" />
        </el-form-item>
      </el-form>
      <p v-if="verifyTarget" class="verify-tip">订单 {{ verifyTarget.id }} · 门店 #{{ verifyForm.shopId }}</p>
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="verifying" @click="submitVerify">确认核销</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.orders-page {
  width: 100%;
}

.order-tabs {
  margin-bottom: 16px;
  padding: 4px 16px 0;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: var(--shadow-sm);
}

.order-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.order-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--line);
}

.order-skeletons,
.order-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.skeleton-order {
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.skeleton-order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.order-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.order-card:hover {
  border-color: var(--brand-200);
  box-shadow: 0 14px 32px rgba(37, 99, 235, 0.08);
}

.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
  border-bottom: 1px solid #f1f7ff;
  background: var(--surface-soft);
}

.order-id {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--faint);
  font-size: 11px;
}

.order-id b {
  overflow: hidden;
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-id small {
  color: var(--faint);
  font-size: 10.5px;
  white-space: nowrap;
}

.order-body {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
}

.order-thumbs {
  display: flex;
  gap: 5px;
  flex: none;
}

.order-thumb {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 54px;
  height: 54px;
  overflow: hidden;
  border-radius: 9px;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
  color: var(--brand-400);
  font-size: 20px;
  font-weight: 800;
}

.order-thumb img {
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.order-items {
  flex: 1;
  min-width: 0;
}

.order-items h3 {
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  font-size: 14.5px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-items h3 span {
  color: var(--muted);
  font-size: 11.5px;
  font-weight: 500;
}

.order-items p {
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 5px;
  color: var(--faint);
  font-size: 10.5px;
}

.order-total {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex: none;
}

.order-total span {
  color: var(--faint);
  font-size: 10.5px;
}

.order-total b {
  color: var(--brand-600);
  font-size: 19px;
  font-weight: 850;
  letter-spacing: -0.02em;
}

.verify-strip {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0 18px 14px;
  padding: 9px 12px;
  border: 1px dashed var(--brand-200);
  border-radius: 10px;
  background: var(--brand-50);
  color: var(--muted);
  font-size: 11px;
}

.verify-strip b {
  color: var(--brand-700);
  font-size: 15px;
  font-weight: 850;
  letter-spacing: 0.06em;
}

.verify-strip small {
  margin-left: auto;
  color: var(--faint);
  font-size: 10.5px;
}

.order-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 18px;
  border-top: 1px solid #f1f7ff;
}

.status-hint {
  color: var(--faint);
  font-size: 10.5px;
}

.order-actions {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: none;
}

.order-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

.empty-orders,
.error-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 350px;
  padding: 40px 24px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  background:
    radial-gradient(circle at 50% 0, rgba(219, 234, 254, 0.5), transparent 55%),
    var(--surface);
  text-align: center;
}

.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 62px;
  height: 62px;
  border-radius: 18px;
  background: var(--brand-50);
  color: var(--brand-500);
  font-size: 28px;
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

.empty-orders h3,
.error-card h3 {
  margin: 16px 0 0;
  font-size: 19px;
  font-weight: 750;
}

.empty-orders p,
.error-card p {
  max-width: 380px;
  margin: 8px 0 18px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}

.drawer-state {
  padding: 8px;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
  border-radius: 12px;
  background: var(--surface-soft);
  border: 1px solid var(--line);
}

.detail-head > div {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.detail-head span {
  color: var(--faint);
  font-size: 10.5px;
}

.detail-head b {
  overflow: hidden;
  color: var(--ink-2);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-steps {
  padding: 6px 0;
}

.big-verify {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 18px;
  border-radius: 14px;
  background:
    radial-gradient(circle at 50% 0, rgba(255, 255, 255, 0.6), transparent 60%),
    linear-gradient(135deg, #eff6ff, #dbeafe);
  border: 1px dashed var(--brand-300);
}

.big-verify span {
  color: var(--brand-600);
  font-size: 11px;
  font-weight: 600;
}

.big-verify b {
  margin-top: 3px;
  color: var(--brand-700);
  font-size: 30px;
  font-weight: 850;
  letter-spacing: 0.12em;
  line-height: 1.2;
}

.big-verify small {
  margin-top: 4px;
  color: var(--muted);
  font-size: 10.5px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--line);
}

.detail-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  background: var(--surface);
}

.detail-grid span {
  color: var(--faint);
  font-size: 10.5px;
}

.detail-grid b {
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 650;
}

.detail-goods h4 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 750;
}

.detail-goods-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-top: 1px solid #f1f7ff;
}

.detail-goods-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  flex: none;
  overflow: hidden;
  border-radius: 8px;
  background: linear-gradient(145deg, #eff6ff, #dbeafe);
  color: var(--brand-500);
  font-size: 18px;
  font-weight: 800;
}

.detail-goods-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.detail-goods-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.detail-goods-info b {
  overflow: hidden;
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-goods-info span {
  color: var(--faint);
  font-size: 10.5px;
}

.detail-goods-price {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex: none;
  line-height: 1.4;
}

.detail-goods-price b {
  color: var(--brand-600);
  font-size: 12.5px;
  font-weight: 750;
}

.detail-goods-price span {
  color: var(--faint);
  font-size: 10px;
}

.detail-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 4px;
}

.verify-form {
  margin-top: 16px;
}

.verify-tip {
  margin: 0;
  color: var(--faint);
  font-size: 11px;
  text-align: right;
}

@media (max-width: 640px) {
  .order-tabs {
    padding: 2px 10px 0;
  }

  .order-head {
    flex-wrap: wrap;
    padding: 11px 13px;
  }

  .order-body {
    flex-wrap: wrap;
    padding: 13px;
  }

  .order-items {
    flex-basis: calc(100% - 70px);
  }

  .order-total {
    width: 100%;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    padding-top: 6px;
  }

  .order-total b {
    font-size: 17px;
  }

  .verify-strip {
    margin: 0 13px 11px;
    flex-wrap: wrap;
  }

  .verify-strip small {
    width: 100%;
    margin-left: 0;
  }

  .order-foot {
    flex-direction: column;
    align-items: flex-start;
    padding: 11px 13px;
  }

  .order-actions {
    width: 100%;
  }

  .order-actions :deep(.el-button) {
    flex: 1;
  }

  .empty-orders,
  .error-card {
    min-height: 300px;
    padding: 30px 18px;
  }
}
</style>
