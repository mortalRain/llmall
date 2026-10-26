<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { orderApi, shopApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'

/**
 * 门店员工核销台。
 *
 * 【为什么单独一个页面】核销是门店员工的动作，不是顾客的动作。顾客在"我的订单"里只需要
 * 看到核销码并到店出示；把"去核销"按钮放在顾客的订单操作里，语义上就在暗示"用户能自己核销"，
 * 正是修复前越权漏洞的产品化表现。这里把核销收敿到独立的员工入口：
 * - 只有 `GET /shops/my/staff` 返回非空的用户才能看到入口/使用表单；
 * - 真正执行时后端仍会强制校验"调用者是该订单门店的员工"，非员工 403（前端判断只是 UX）。
 *
 * 【注意】页面上的身份判断不能替代后端校验：`/shops/my/staff` 只用于展示与导航，
 * 伪造它不会带来任何权限。
 */
const router = useRouter()

const checking = ref(true)
const staffShops = ref<Array<{ shopId: string; role: string }>>([])
const staffShopIds = computed(() => staffShops.value.map((item) => String(item.shopId)))
const checkError = ref('')
const form = reactive({
  verifyCode: '',
  shopId: '',
})
const submitting = ref(false)
const submitError = ref('')
const lastOrderId = ref('')

const allowed = computed(() => staffShopIds.value.length > 0)
const denyReason = computed(() => {
  if (checkError.value) {
    return `员工身份校验失败：${checkError.value}`
  }
  return '当前账号不是任何门店的员工。核销台只对门店员工开放；如刚入职，请联系门店管理员登记。'
})

async function loadStaffShops(): Promise<void> {
  checking.value = true
  checkError.value = ''
  try {
    const shops = (await shopApi.myStaffShops()) as unknown as Array<
      { shopId: string; role: string } | string
    >
    // 兼容过渡态：老后端返回 ["10"]，新后端返回 [{shopId, role}]
    staffShops.value = Array.isArray(shops)
      ? shops.map((item) => (typeof item === 'string' ? { shopId: item, role: 'STAFF' } : item))
      : []
    // 默认选中第一家任职门店：多数员工只在一家店，少一次点击
    if (staffShopIds.value.length && !staffShopIds.value.includes(form.shopId)) {
      form.shopId = staffShopIds.value[0]
    }
  } catch (error) {
    // 查不到身份按"无权限"处理（fail-closed）：宁可不展示表单，也不能把查询失败当成有权限
    staffShops.value = []
    checkError.value = getErrorMessage(error, '无法确认员工身份')
  } finally {
    checking.value = false
  }
}

async function submit(): Promise<void> {
  if (!form.verifyCode.trim()) {
    ElMessage.warning('请输入顾客出示的核销码')
    return
  }
  if (!form.shopId) {
    ElMessage.warning('请选择核销门店')
    return
  }

  submitting.value = true
  submitError.value = ''
  lastOrderId.value = ''
  try {
    const orderId = await orderApi.verify({
      verifyCode: form.verifyCode.trim().toUpperCase(),
      shopId: form.shopId,
    })
    lastOrderId.value = String(orderId)
    form.verifyCode = ''
    ElMessage.success('核销成功')
  } catch (error) {
    // 403（非本店员工）/400（码不存在/已使用/门店不匹配）都原样展示服务端文案
    submitError.value = getErrorMessage(error, '核销失败，请核对核销码与门店')
    ElMessage.error(submitError.value)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadStaffShops()
})
</script>

<template>
  <div class="staff-page">
    <PageHeader
      eyebrow="STAFF CONSOLE"
      title="核销台"
      subtitle="门店员工专用：核对顾客出示的核销码，选择本店后完成核销。"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="checking" @click="loadStaffShops">刷新员工身份</el-button>
      </template>
    </PageHeader>

    <div v-if="checking" class="panel">
      <el-skeleton :rows="5" animated />
    </div>

    <el-result
      v-else-if="!allowed"
      class="panel deny-panel"
      icon="warning"
      title="仅门店员工可使用核销台"
      :sub-title="denyReason"
    >
      <template #extra>
        <el-button type="primary" @click="router.push('/shops')">去逛门店</el-button>
        <el-button @click="router.push('/orders')">返回我的订单</el-button>
      </template>
    </el-result>

    <template v-else>
      <section class="panel verify-panel">
        <div class="panel-head">
          <div>
            <h2>员工身份</h2>
            <p>
              当前账号任职门店：<b>#{{ staffShopIds.join('、#') }}</b>
            </p>
          </div>
          <el-tag type="success" effect="light">员工模式</el-tag>
        </div>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="核销是不可逆的终态"
          description="核销后订单变为「已核销」、顾客的券即视为已使用。请当面核对顾客出示的核销码与本店门店，非本店员工提交会被服务端以 403 拒绝。"
        />

        <el-form label-position="top" class="verify-form" @submit.prevent="submit">
          <el-form-item label="核销门店">
            <div class="shop-picker">
              <el-button
                v-for="shopId in staffShopIds"
                :key="shopId"
                :type="form.shopId === shopId ? 'primary' : 'default'"
                @click="form.shopId = shopId"
              >
                门店 #{{ shopId }}
              </el-button>
            </div>
          </el-form-item>
          <el-form-item label="核销码">
            <el-input
              v-model="form.verifyCode"
              size="large"
              maxlength="16"
              placeholder="请输入顾客出示的核销码"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-button class="submit-button" type="primary" size="large" :loading="submitting" @click="submit">
            确认核销
          </el-button>
        </el-form>

        <el-alert
          v-if="submitError"
          class="submit-error"
          type="error"
          :closable="false"
          show-icon
          :title="submitError"
        />
      </section>

      <section v-if="lastOrderId" class="panel result-panel">
        <el-result
          icon="success"
          title="核销成功"
          :sub-title="`订单 ${lastOrderId} 已核销`"
        >
          <template #extra>
            <el-button type="primary" @click="router.push('/orders')">查看订单</el-button>
            <el-button @click="lastOrderId = ''">继续核销</el-button>
          </template>
        </el-result>
      </section>
    </template>
  </div>
</template>

<style scoped>
.staff-page {
  width: 100%;
  max-width: 760px;
}

.panel {
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.verify-panel + .result-panel,
.panel + .panel {
  margin-top: 16px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 750;
}

.panel-head p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.panel-head b {
  color: var(--brand-600);
}

.verify-form {
  margin-top: 18px;
}

.shop-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.submit-button {
  width: 100%;
  height: 44px;
}

.submit-error {
  margin-top: 14px;
}

.deny-panel {
  border: 1px dashed var(--line-strong);
}

@media (max-width: 640px) {
  .panel {
    padding: 16px;
  }
}
</style>
