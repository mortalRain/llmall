<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { shopApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import type { MyShopStaff, ShopStaffMember, StaffRole } from '@/api/types'

/**
 * 商家员工管理页（仅 OWNER/MANAGER 可见）。
 *
 * 【权限模型】角色三档，核销权人人都有，管理权逐级收紧：
 * - OWNER 店主：增删员工、改角色、授予/撤销 OWNER；
 * - MANAGER 店长：增删 STAFF/MANAGER、改他们的角色；不能碰 OWNER；
 * - STAFF 员工：只能核销，没有管理入口。
 *
 * 【前端只做体验，不做安全边界】这里所有按钮的置灰/隐藏都是为了让合法用户少犯错；
 * 真正的拒绝在服务端：STAFF 调管理接口 403、店长授予 OWNER 403、删除最后一个 OWNER 409。
 * 所以即使有人改前端代码强行发请求，也不会越权。
 *
 * 【为什么员工列表只显示 userId】门店域只持有 user_id（跨库逻辑外键），
 * 不复制用户资料；要显示用户名需要额外调用户域，当前需求只要"按 userId 添加/管理"，
 * 不为此引入跨服务批量查询。
 */
const ROLE_LABELS: Record<StaffRole, string> = {
  OWNER: '店主',
  MANAGER: '店长',
  STAFF: '员工',
}
const ROLE_OPTIONS: StaffRole[] = ['OWNER', 'MANAGER', 'STAFF']

const router = useRouter()

interface EditableMember extends ShopStaffMember {
  draftRole: StaffRole
}

const checking = ref(true)
const checkError = ref('')
const managerShops = ref<MyShopStaff[]>([])
const selectedShopId = ref('')
const members = ref<EditableMember[]>([])
const loadingMembers = ref(false)
const adding = ref(false)
const savingUserId = ref('')
const removingUserId = ref('')
const addForm = reactive<{ userId: string; role: StaffRole }>({
  userId: '',
  role: 'STAFF',
})

const currentShop = computed(() =>
  managerShops.value.find((shop) => String(shop.shopId) === selectedShopId.value),
)
const currentRole = computed<StaffRole>(() => currentShop.value?.role ?? 'STAFF')
const isOwner = computed(() => currentRole.value === 'OWNER')
const allowed = computed(() => managerShops.value.length > 0)
const ownerCount = computed(() => members.value.filter((member) => member.role === 'OWNER').length)

const denyReason = computed(() => {
  if (checkError.value) {
    return `员工身份校验失败：${checkError.value}`
  }
  return '员工管理只对门店店主（OWNER）/店长（MANAGER）开放。如果你是员工，请让店主或店长添加你并授予管理角色。'
})

function roleLabel(role: string): string {
  return ROLE_LABELS[role as StaffRole] || role
}

function roleTagType(role: string): 'danger' | 'warning' | 'info' {
  if (role === 'OWNER') return 'danger'
  if (role === 'MANAGER') return 'warning'
  return 'info'
}

/** 最后一个店主不能被降级/删除：与服务端 409 的规则一致，提前置灰避免误操作 */
function isLastOwner(member: EditableMember): boolean {
  return member.role === 'OWNER' && ownerCount.value <= 1
}

/** 店长只能把非店主改成 MANAGER/STAFF；店主可改任意角色 */
function selectableRoles(member: EditableMember): StaffRole[] {
  if (isOwner.value) {
    return ROLE_OPTIONS
  }
  if (member.role === 'OWNER') {
    return ['OWNER']
  }
  return ['MANAGER', 'STAFF']
}

function canSave(member: EditableMember): boolean {
  if (member.draftRole === member.role || isLastOwner(member)) {
    return false
  }
  if (!isOwner.value && (member.role === 'OWNER' || member.draftRole === 'OWNER')) {
    return false
  }
  return true
}

function canRemove(member: EditableMember): boolean {
  if (isLastOwner(member)) {
    return false
  }
  return isOwner.value || member.role !== 'OWNER'
}

async function loadManagerShops(): Promise<void> {
  checking.value = true
  checkError.value = ''
  try {
    const shops = (await shopApi.myStaffShops()) as unknown as Array<MyShopStaff | string>
    // 兼容过渡态：老后端返回 ["10"]，新后端返回 [{shopId, role}]
    const normalized = (Array.isArray(shops) ? shops : []).map((item) =>
      typeof item === 'string' ? { shopId: item, role: 'STAFF' as const } : item,
    )
    managerShops.value = normalized.filter(
      (shop) => shop.role === 'OWNER' || shop.role === 'MANAGER',
    )
    const stillSelectable = managerShops.value.some(
      (shop) => String(shop.shopId) === selectedShopId.value,
    )
    if (!stillSelectable) {
      selectedShopId.value = managerShops.value.length
        ? String(managerShops.value[0].shopId)
        : ''
    }
    if (selectedShopId.value) {
      await loadMembers()
    }
  } catch (error) {
    // 查不到身份按"无权限"处理（fail-closed）
    managerShops.value = []
    members.value = []
    checkError.value = getErrorMessage(error, '无法确认管理权限')
  } finally {
    checking.value = false
  }
}

async function loadMembers(): Promise<void> {
  if (!selectedShopId.value) {
    members.value = []
    return
  }
  loadingMembers.value = true
  try {
    const rows = await shopApi.listStaff(selectedShopId.value)
    members.value = (Array.isArray(rows) ? rows : []).map((row) => ({
      ...row,
      draftRole: row.role,
    }))
  } catch (error) {
    members.value = []
    ElMessage.error(getErrorMessage(error, '员工列表加载失败'))
  } finally {
    loadingMembers.value = false
  }
}

async function selectShop(shopId: string): Promise<void> {
  if (selectedShopId.value === shopId) {
    return
  }
  selectedShopId.value = shopId
  await loadMembers()
}

async function addMember(): Promise<void> {
  const userId = addForm.userId.trim()
  if (!/^\d+$/.test(userId) || userId === '0') {
    ElMessage.warning('请输入合法的用户 ID（数字）')
    return
  }
  adding.value = true
  try {
    await shopApi.addStaff(selectedShopId.value, { userId, role: addForm.role })
    ElMessage.success(`已添加用户 ${userId}（${roleLabel(addForm.role)}）`)
    addForm.userId = ''
    await loadMembers()
  } catch (error) {
    // 重复添加返回 409，服务端 msg 直接展示
    ElMessage.error(getErrorMessage(error, '添加员工失败'))
  } finally {
    adding.value = false
  }
}

async function saveRole(member: EditableMember): Promise<void> {
  if (!canSave(member)) {
    return
  }
  savingUserId.value = String(member.userId)
  try {
    await shopApi.updateStaffRole(selectedShopId.value, String(member.userId), member.draftRole)
    ElMessage.success(`用户 ${member.userId} 已改为${roleLabel(member.draftRole)}`)
    await loadMembers()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '修改角色失败'))
    // 失败时撤回本地草稿，避免界面显示一个服务端并未接受的角色
    member.draftRole = member.role
  } finally {
    savingUserId.value = ''
  }
}

async function removeMember(member: EditableMember): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认把用户 ${member.userId}（${roleLabel(member.role)}）移出门店？移出后该用户将不能再核销本店订单。`,
      '移出员工',
      { confirmButtonText: '移出', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  removingUserId.value = String(member.userId)
  try {
    await shopApi.removeStaff(selectedShopId.value, String(member.userId))
    ElMessage.success(`用户 ${member.userId} 已移出本店`)
    await loadMembers()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '删除员工失败'))
  } finally {
    removingUserId.value = ''
  }
}

onMounted(() => {
  void loadManagerShops()
})
</script>

<template>
  <div class="staff-manage-page">
    <PageHeader
      eyebrow="STAFF ADMIN"
      title="员工管理"
      subtitle="店主/店长可添加、调整、移出本店员工；核销权限对所有角色开放，管理权限仅店主与店长。"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="checking || loadingMembers" @click="loadManagerShops">
          刷新
        </el-button>
      </template>
    </PageHeader>

    <div v-if="checking" class="panel">
      <el-skeleton :rows="5" animated />
    </div>

    <el-result
      v-else-if="!allowed"
      class="panel deny-panel"
      icon="warning"
      title="仅店主/店长可使用员工管理"
      :sub-title="denyReason"
    >
      <template #extra>
        <el-button type="primary" @click="router.push('/staff/verify')">去核销台</el-button>
        <el-button @click="router.push('/profile')">返回我的</el-button>
      </template>
    </el-result>

    <template v-else>
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>管理门店</h2>
            <p>你以 <b>{{ roleLabel(currentRole) }}</b> 身份管理以下门店</p>
          </div>
          <el-tag :type="roleTagType(currentRole)" effect="light">
            {{ roleLabel(currentRole) }}
          </el-tag>
        </div>

        <div class="shop-picker">
          <el-button
            v-for="shop in managerShops"
            :key="String(shop.shopId)"
            :type="selectedShopId === String(shop.shopId) ? 'primary' : 'default'"
            @click="selectShop(String(shop.shopId))"
          >
            门店 #{{ shop.shopId }} · {{ roleLabel(shop.role) }}
          </el-button>
        </div>

        <el-alert
          class="role-note"
          type="info"
          :closable="false"
          show-icon
          title="角色权限说明"
          description="店主：管理员工 + 核销 + 授予/撤销店主；店长：管理员工 + 核销，但不能授予/撤销店主；员工：仅核销。门店必须至少保留一个店主。"
        />

        <el-form class="add-form" @submit.prevent="addMember">
          <el-form-item label="用户 ID">
            <el-input
              v-model="addForm.userId"
              placeholder="输入要添加的用户 ID（llmall-user.user.id）"
              clearable
              @keyup.enter="addMember"
            />
          </el-form-item>
          <el-form-item label="角色">
            <el-select v-model="addForm.role" class="role-select">
              <el-option
                v-for="role in ROLE_OPTIONS"
                :key="role"
                :label="roleLabel(role)"
                :value="role"
                :disabled="role === 'OWNER' && !isOwner"
              />
            </el-select>
          </el-form-item>
          <el-button type="primary" :icon="Plus" :loading="adding" @click="addMember">
            添加员工
          </el-button>
        </el-form>
      </section>

      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>员工列表</h2>
            <p>共 {{ members.length }} 人，其中店主 {{ ownerCount }} 人</p>
          </div>
          <el-button text :loading="loadingMembers" @click="loadMembers">刷新列表</el-button>
        </div>

        <el-table v-loading="loadingMembers" :data="members" class="staff-table" empty-text="该门店还没有员工">
          <el-table-column prop="userId" label="用户 ID" min-width="140" />
          <el-table-column label="角色" min-width="100">
            <template #default="{ row }">
              <el-tag :type="roleTagType(row.role)" effect="light">{{ roleLabel(row.role) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="加入时间" min-width="180">
            <template #default="{ row }">
              {{ row.createTime || '—' }}
            </template>
          </el-table-column>
          <el-table-column label="调整角色" min-width="210">
            <template #default="{ row }">
              <div class="role-editor">
                <el-select v-model="row.draftRole" size="small" :disabled="isLastOwner(row)">
                  <el-option
                    v-for="role in selectableRoles(row)"
                    :key="role"
                    :label="roleLabel(role)"
                    :value="role"
                  />
                </el-select>
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :disabled="!canSave(row)"
                  :loading="savingUserId === String(row.userId)"
                  @click="saveRole(row)"
                >
                  保存
                </el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                size="small"
                type="danger"
                text
                :icon="Delete"
                :disabled="!canRemove(row)"
                :loading="removingUserId === String(row.userId)"
                @click="removeMember(row)"
              >
                移出
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <p class="hint">
          最后一个店主不能降级或移出；店长不能修改店主、也不能授予店主。按钮置灰只是提示，服务端会再次校验。
        </p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.staff-manage-page {
  width: 100%;
  max-width: 980px;
}

.panel {
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

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

.shop-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.role-note {
  margin-top: 16px;
}

.add-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px dashed var(--line);
}

.add-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.add-form :deep(.el-input) {
  width: 280px;
}

.role-select {
  width: 140px;
}

.staff-table {
  width: 100%;
}

.role-editor {
  display: flex;
  align-items: center;
  gap: 8px;
}

.role-editor :deep(.el-select) {
  width: 100px;
}

.hint {
  margin: 14px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.deny-panel {
  border: 1px dashed var(--line-strong);
}

@media (max-width: 640px) {
  .panel {
    padding: 16px;
  }

  .add-form :deep(.el-input) {
    width: 100%;
  }

  .add-form :deep(.el-form-item) {
    width: 100%;
  }
}
</style>
