<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; changed: [] }>()
const form = reactive({ phone: '', oldPassword: '', newPassword: '', confirmPassword: '', code: '' })
const sending = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const cooldown = ref(0)
const busy = computed(() => sending.value || submitting.value)
let timer: ReturnType<typeof setInterval> | undefined

function clearSecrets(): void {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  form.code = ''
}
function reset(): void {
  clearSecrets()
  form.phone = ''
  errorMessage.value = ''
  cooldown.value = 0
  clearInterval(timer)
  timer = undefined
}
function close(): void { if (!busy.value) emit('update:modelValue', false) }
watch(() => props.modelValue, open => { if (!open) reset() })
onBeforeUnmount(reset)

async function sendCode(): Promise<void> {
  if (busy.value || cooldown.value > 0) return
  if (!/^1[3-9]\d{9}$/.test(form.phone.trim())) {
    errorMessage.value = '请输入账号绑定的11位手机号'
    return
  }
  sending.value = true
  errorMessage.value = ''
  try {
    await authApi.sendPayPasswordCode(form.phone.trim())
    cooldown.value = 60
    clearInterval(timer)
    timer = setInterval(() => {
      cooldown.value--
      if (cooldown.value <= 0) { clearInterval(timer); timer = undefined }
    }, 1000)
    ElMessage.success('验证码已发送至该手机号，请查收')
  } catch (error) { errorMessage.value = getErrorMessage(error, '验证码发送失败') }
  finally { sending.value = false }
}

async function submit(): Promise<void> {
  if (busy.value) return
  errorMessage.value = ''
  if (!form.oldPassword.trim()) errorMessage.value = '请输入原支付口令'
  else if (!form.newPassword.trim() || form.newPassword.length < 6 || form.newPassword.length > 32) {
    errorMessage.value = '新支付口令需为6–32位'
  } else if (form.newPassword === form.oldPassword || form.newPassword === '123456') {
    errorMessage.value = '新口令不能与原口令相同或使用初始口令'
  } else if (form.newPassword !== form.confirmPassword) errorMessage.value = '两次新口令不一致'
  else if (!/^\d{6}$/.test(form.code.trim())) errorMessage.value = '请输入6位短信验证码'
  if (errorMessage.value) return
  submitting.value = true
  try {
    await authApi.changePayPassword({ oldPassword: form.oldPassword, newPassword: form.newPassword, code: form.code.trim() })
    clearSecrets()
    emit('update:modelValue', false)
    emit('changed')
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '修改未确认，请核对结果后重试')
    form.code = '' // The backend consumes a scene-specific code before checking the old password.
  } finally { submitting.value = false }
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="修改支付口令" width="min(460px, 92vw)"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy"
    @update:model-value="close">
    <p class="pay-password-hint">使用原支付口令和绑定手机的验证码修改。成功后需重新登录。</p>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="原支付口令">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" placeholder="原支付口令" :disabled="busy" />
      </el-form-item>
      <el-form-item label="新支付口令">
        <el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" placeholder="新支付口令（6–32位）" maxlength="32" :disabled="busy" />
      </el-form-item>
      <el-form-item label="确认新支付口令">
        <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" placeholder="再次输入新支付口令" maxlength="32" :disabled="busy" />
      </el-form-item>
      <el-form-item label="账号绑定手机号">
        <el-input v-model="form.phone" placeholder="账号绑定手机号" inputmode="tel" maxlength="11" :disabled="busy" autocomplete="tel" />
      </el-form-item>
      <el-form-item label="短信验证码">
        <div class="code-row">
          <el-input v-model="form.code" placeholder="6位短信验证码" inputmode="numeric" maxlength="6" :disabled="busy" autocomplete="one-time-code" />
          <el-button :loading="sending" :disabled="busy || cooldown > 0" @click="sendCode">
            {{ cooldown > 0 ? `${cooldown}秒后重发` : '发送验证码' }}
          </el-button>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="busy" @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="busy" @click="submit">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pay-password-hint { margin: 0 0 12px; color: var(--el-text-color-secondary); }
.code-row { display: flex; width: 100%; gap: 8px; }
.el-alert { margin-bottom: 12px; }
</style>
