<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowRight, Compass, Lock, Shop, User, Wallet } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { getErrorMessage } from '@/api/http'

interface LoginForm {
  username: string
  password: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const form = reactive<LoginForm>({
  username: '',
  password: '',
})
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入登录密码', trigger: 'blur' }],
}
const loading = ref(false)
const errorMessage = ref('')

const redirectTarget = computed(() => {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//')) {
    return '/feed'
  }
  if (redirect.startsWith('/login')) return '/feed'
  return redirect
})

function fillDemo(): void {
  form.username = 'Jack'
  form.password = '123456'
  errorMessage.value = ''
}

async function submit(): Promise<void> {
  if (!formRef.value || loading.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  errorMessage.value = ''
  try {
    await auth.login({ username: form.username.trim(), password: form.password })
    ElMessage.success(`欢迎回来，${auth.displayName}`)
    await router.replace(redirectTarget.value)
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '登录失败，请检查账号与密码')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="brand-panel">
      <div class="orb orb-one"></div>
      <div class="orb orb-two"></div>
      <div class="brand-top">
        <span class="brand-mark large">L</span>
        <div class="brand-name">
          <b>LLMall</b>
          <span>同城生活</span>
        </div>
      </div>

      <div class="brand-copy">
        <span class="eyebrow">LOCAL LIFE, REIMAGINED</span>
        <h1>把同城的好店<br /><em>装进每一天</em></h1>
        <p>关注同城动态，发现附近好店，领一张券，下一单，到店慢慢享受。</p>

        <ul class="feature-list">
          <li>
            <span class="feature-icon"><el-icon><Compass /></el-icon></span>
            <div>
              <b>关注动态</b>
              <span>同城作者的真实探店分享</span>
            </div>
          </li>
          <li>
            <span class="feature-icon"><el-icon><Shop /></el-icon></span>
            <div>
              <b>精选门店</b>
              <span>美食、休闲、玩乐一站发现</span>
            </div>
          </li>
          <li>
            <span class="feature-icon"><el-icon><Wallet /></el-icon></span>
            <div>
              <b>余额支付</b>
              <span>下单即锁券，到店出示核销码</span>
            </div>
          </li>
        </ul>
      </div>

      <div class="demo-panel">
        <div class="demo-head">
          <span>演示账号</span>
          <span class="demo-badge">DEMO</span>
        </div>
        <div class="demo-grid">
          <div class="demo-item">
            <small>账号</small>
            <b>Jack</b>
          </div>
          <div class="demo-item">
            <small>密码</small>
            <b>123456</b>
          </div>
          <el-button class="demo-fill" size="small" @click="fillDemo">一键填入</el-button>
        </div>
      </div>

      <footer class="brand-footer">LLMall · 让同城生活更近一点</footer>
    </section>

    <section class="form-panel">
      <div class="form-wrap">
        <div class="mobile-brand">
          <span class="brand-mark">L</span>
          <div>
            <b>LLMall</b>
            <span>同城生活</span>
          </div>
        </div>

        <div class="form-head">
          <span class="eyebrow">WELCOME BACK</span>
          <h2>登录账户</h2>
          <p>继续发现身边的好店与同城动态。</p>
        </div>

        <el-alert
          v-if="errorMessage"
          class="login-alert"
          :title="errorMessage"
          type="error"
          show-icon
          :closable="true"
          @close="errorMessage = ''"
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              :prefix-icon="User"
              placeholder="请输入用户名"
              autocomplete="username"
              clearable
            />
          </el-form-item>
          <el-form-item label="登录密码" prop="password">
            <el-input
              v-model="form.password"
              :prefix-icon="Lock"
              type="password"
              show-password
              placeholder="请输入登录密码"
              autocomplete="current-password"
              @keyup.enter="submit"
            />
          </el-form-item>

          <el-button class="submit-button" type="primary" native-type="submit" :loading="loading">
            <span>进入 LLMall</span>
            <el-icon v-if="!loading"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <div class="demo-inline">
          <span>演示账号</span>
          <code>Jack</code>
          <span>/</span>
          <code>123456</code>
          <el-button link type="primary" @click="fillDemo">填入</el-button>
        </div>

        <p class="form-note">
          本项目为同城生活电商演示工程，登录即代表你同意仅将数据用于本地联调与演示。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(420px, 0.92fr);
  min-height: 100vh;
  background: #fff;
}

.brand-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  padding: 40px 52px 30px;
  overflow: hidden;
  background:
    radial-gradient(circle at 82% 18%, rgba(255, 255, 255, 0.85), transparent 34%),
    linear-gradient(145deg, #f4f9ff 0%, #e3efff 48%, #cfe4ff 100%);
}

.orb {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.orb-one {
  top: -120px;
  right: -90px;
  width: 380px;
  height: 380px;
  background: radial-gradient(circle, rgba(147, 197, 253, 0.55), transparent 68%);
}

.orb-two {
  bottom: -150px;
  left: -120px;
  width: 440px;
  height: 440px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.75), transparent 65%);
}

.brand-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-mark.large {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  font-size: 24px;
}

.brand-name {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.brand-name b {
  color: var(--brand-800, #1e3a8a);
  font-size: 20px;
  font-weight: 850;
  letter-spacing: -0.03em;
}

.brand-name span {
  margin-top: 2px;
  color: var(--brand-600);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
}

.brand-copy {
  position: relative;
  z-index: 1;
  max-width: 560px;
  margin: auto 0;
  padding: 44px 0 36px;
}

.brand-copy h1 {
  margin: 13px 0 0;
  color: #0b2a52;
  font-size: clamp(38px, 4.4vw, 58px);
  font-weight: 850;
  line-height: 1.14;
  letter-spacing: -0.045em;
}

.brand-copy h1 em {
  position: relative;
  color: var(--brand-600);
  font-style: normal;
}

.brand-copy h1 em::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 2px;
  left: 0;
  height: 9px;
  border-radius: 999px;
  background: rgba(147, 197, 253, 0.55);
  z-index: -1;
}

.brand-copy > p {
  max-width: 440px;
  margin: 18px 0 0;
  color: #3d5f8a;
  font-size: 15px;
  line-height: 1.8;
}

.feature-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 34px 0 0;
  padding: 0;
  list-style: none;
}

.feature-list li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px;
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(10px);
}

.feature-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9px;
  background: #fff;
  color: var(--brand-500);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.12);
}

.feature-list b {
  display: block;
  color: #12365f;
  font-size: 12.5px;
  font-weight: 750;
}

.feature-list span:not(.feature-icon) {
  display: block;
  margin-top: 2px;
  color: #5b7ca6;
  font-size: 11px;
  line-height: 1.5;
}

.demo-panel {
  position: relative;
  z-index: 1;
  width: min(100%, 430px);
  padding: 15px 18px;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 18px 40px rgba(37, 99, 235, 0.09);
  backdrop-filter: blur(14px);
}

.demo-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #274b74;
  font-size: 12px;
  font-weight: 700;
}

.demo-badge {
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--brand-100);
  color: var(--brand-600);
  font-size: 9.5px;
  letter-spacing: 0.1em;
}

.demo-grid {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-top: 9px;
}

.demo-item {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.demo-item small {
  color: #6d8bac;
  font-size: 10.5px;
}

.demo-item b {
  color: #12365f;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 14px;
}

.demo-fill {
  margin-left: auto;
  border-color: var(--brand-200);
  color: var(--brand-600);
  background: #fff;
}

.brand-footer {
  position: relative;
  z-index: 1;
  margin-top: 24px;
  color: #7795b7;
  font-size: 11px;
  letter-spacing: 0.04em;
}

.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 48px 48px;
  background: #fff;
}

.form-wrap {
  width: min(100%, 400px);
}

.mobile-brand {
  display: none;
}

.form-head .eyebrow {
  color: var(--brand-500);
}

.form-head h2 {
  margin: 8px 0 0;
  color: var(--ink);
  font-size: 32px;
  font-weight: 850;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.form-head p {
  margin: 9px 0 0;
  color: var(--muted);
  font-size: 13.5px;
}

.login-alert {
  margin-top: 18px;
}

.form-wrap :deep(.el-form) {
  margin-top: 24px;
}

.form-wrap :deep(.el-form-item) {
  margin-bottom: 18px;
}

.form-wrap :deep(.el-form-item__label) {
  padding-bottom: 5px;
  font-size: 12.5px;
}

.form-wrap :deep(.el-input__wrapper) {
  background: var(--surface-soft);
}

.submit-button {
  width: 100%;
  height: 46px;
  margin-top: 4px;
  font-size: 14.5px;
}

.submit-button span {
  margin-right: 6px;
}

.demo-inline {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 20px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--brand-50);
  color: var(--muted);
  font-size: 12px;
}

.demo-inline code {
  padding: 1px 5px;
  border-radius: 5px;
  background: #fff;
  color: var(--brand-700);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.form-note {
  margin: 22px 0 0;
  color: var(--faint);
  font-size: 11px;
  line-height: 1.7;
  text-align: center;
}

@media (max-width: 1100px) {
  .brand-panel {
    padding: 32px 34px 26px;
  }

  .feature-list {
    grid-template-columns: 1fr;
    gap: 8px;
    margin-top: 24px;
  }

  .feature-list li {
    padding: 10px 12px;
  }
}

@media (max-width: 900px) {
  .login-page {
    display: block;
    min-height: 100vh;
    background: linear-gradient(180deg, #eff7ff 0%, #f5f9ff 42%, #fff 100%);
  }

  .brand-panel {
    display: none;
  }

  .form-panel {
    min-height: 100vh;
    padding: 28px 22px 40px;
    background: transparent;
  }

  .form-wrap {
    width: min(100%, 430px);
    padding: 26px 22px 28px;
    border: 1px solid var(--line);
    border-radius: 22px;
    background: rgba(255, 255, 255, 0.94);
    box-shadow: var(--shadow);
  }

  .mobile-brand {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 26px;
  }

  .mobile-brand > div {
    display: flex;
    flex-direction: column;
    line-height: 1.15;
  }

  .mobile-brand b {
    font-size: 17px;
    font-weight: 850;
  }

  .mobile-brand span {
    color: var(--muted);
    font-size: 10.5px;
    letter-spacing: 0.12em;
  }

  .form-head h2 {
    font-size: 28px;
  }
}

@media (max-width: 420px) {
  .form-panel {
    padding: 18px 14px 30px;
  }

  .form-wrap {
    padding: 22px 16px 24px;
    border-radius: 18px;
  }

  .form-head h2 {
    font-size: 26px;
  }

  .demo-inline {
    flex-wrap: wrap;
  }
}
</style>
