<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  ArrowLeft,
  ChatDotRound,
  Location,
  Picture,
  Promotion,
  Shop,
  StarFilled,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StateBlock from '@/components/StateBlock.vue'
import { blogApi, shopApi } from '@/api/modules'
import { getErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { Shop as ShopDetail } from '@/api/types'
import { formatPrice, initialOf } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const shopId = computed(() => String(route.params.id || ''))
const shop = ref<ShopDetail | null>(null)
const loading = ref(true)
const errorMessage = ref('')

const formRef = ref<FormInstance>()
const submitting = ref(false)
const submitError = ref('')
const form = reactive({
  score: 0,
  title: '',
  content: '',
  imageUrl: '',
})

/** 与后端 BlogFormDTO 的约束对齐：评分必填 1~5、标题非空、正文非空且不超过 2048（本页再收紧到 10~500） */
const rules = {
  score: [
    {
      required: true,
      type: 'number',
      min: 1,
      max: 5,
      message: '请选择 1~5 星评分',
      trigger: 'change',
    },
  ],
  title: [
    { required: true, message: '请填写评价标题', trigger: 'blur' },
    { max: 50, message: '标题最多 50 字', trigger: 'blur' },
  ],
  content: [
    { required: true, message: '请填写评价内容', trigger: 'blur' },
    { min: 10, max: 500, message: '评价内容需 10~500 字', trigger: 'blur' },
  ],
  imageUrl: [
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        const text = String(value || '').trim()
        if (!text || /^https?:\/\/\S+$/i.test(text)) {
          callback()
          return
        }
        callback(new Error('请输入 http(s) 开头的图片地址'))
      },
      trigger: 'blur',
    },
  ],
} as FormRules

const contentLength = computed(() => form.content.trim().length)
const scoreText = computed(() => {
  const labels = ['未评分', '很差', '较差', '一般', '不错', '很好']
  return labels[Math.min(5, Math.max(0, Math.round(form.score)))]
})
const shopInitial = computed(() => initialOf(shop.value?.name))

async function loadShop(): Promise<void> {
  if (!shopId.value) {
    errorMessage.value = '缺少门店 id'
    loading.value = false
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    shop.value = await shopApi.detail(shopId.value)
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '门店信息加载失败')
  } finally {
    loading.value = false
  }
}

function backToShop(): void {
  void router.push({ name: 'shop-detail', params: { id: shopId.value } })
}

async function submit(): Promise<void> {
  if (submitting.value) return
  if (!auth.isLoggedIn) {
    await router.push({
      name: 'login',
      query: { redirect: `/shops/${shopId.value}/review` },
    })
    return
  }
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  submitError.value = ''
  try {
    await blogApi.create({
      shopId: shopId.value,
      title: form.title.trim(),
      content: form.content.trim(),
      score: Number(form.score),
      ...(form.imageUrl.trim() ? { images: form.imageUrl.trim() } : {}),
    })
    ElMessage.success('评价发布成功')
    // 回到门店详情并定位到评价区：组件会重新拉取评价，新评价出现在列表首位
    await router.replace({
      name: 'shop-detail',
      params: { id: shopId.value },
      query: { reviewed: '1' },
      hash: '#reviews',
    })
  } catch (error) {
    submitError.value = getErrorMessage(error, '评价发布失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadShop()
})
</script>

<template>
  <div class="review-page">
    <div class="review-top">
      <el-button text :icon="ArrowLeft" @click="router.back()">返回</el-button>
      <span class="crumb">
        门店 / {{ shop?.name || shopId }} / 写评价
      </span>
    </div>

    <StateBlock
      :loading="loading"
      :error="errorMessage"
      :empty="!loading && !errorMessage && !shop"
      empty-text="门店不存在或已下线"
      @retry="loadShop"
    >
      <PageHeader
        eyebrow="WRITE A REVIEW"
        title="写评价"
        subtitle="分享真实的到店体验，帮助同城用户发现好店。"
      />

      <div class="review-layout">
        <section class="review-card" data-testid="review-form">
          <header class="shop-strip" v-if="shop">
            <span class="shop-avatar"><el-icon><Shop /></el-icon></span>
            <div class="shop-strip-info">
              <b>{{ shop.name }}</b>
              <span>
                <el-icon><Location /></el-icon>
                {{ shop.area ? `${shop.area} · ` : '' }}{{ shop.address || '地址待完善' }}
              </span>
            </div>
            <span class="shop-price">人均 {{ formatPrice(shop.avgPrice) }}</span>
          </header>

          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            class="review-form"
            @submit.prevent
          >
            <el-form-item label="总体评分" prop="score" class="score-item">
              <div class="score-row">
                <el-rate
                  v-model="form.score"
                  :max="5"
                  :show-score="false"
                  size="large"
                  data-testid="review-score"
                />
                <span class="score-text" :class="{ filled: form.score > 0 }">
                  <el-icon v-if="form.score > 0"><StarFilled /></el-icon>
                  {{ scoreText }}
                  <b v-if="form.score > 0">{{ Number(form.score).toFixed(1) }}</b>
                </span>
              </div>
            </el-form-item>

            <el-form-item label="评价标题" prop="title">
              <el-input
                v-model="form.title"
                maxlength="50"
                show-word-limit
                clearable
                placeholder="一句话概括这次体验，例如：包厢音效不错，适合团建"
                data-testid="review-title"
              />
            </el-form-item>

            <el-form-item label="评价内容" prop="content">
              <el-input
                v-model="form.content"
                type="textarea"
                :rows="6"
                maxlength="500"
                show-word-limit
                resize="vertical"
                placeholder="说说环境、服务、口味或性价比，至少 10 个字"
                data-testid="review-content"
              />
              <div class="content-hint">
                <span :class="{ warn: contentLength > 0 && contentLength < 10 }">
                  已输入 {{ contentLength }} 字，需 10~500 字
                </span>
                <span class="hint-tip">
                  <el-icon><ChatDotRound /></el-icon>
                  真实、具体的体验更容易被其他用户参考
                </span>
              </div>
            </el-form-item>

            <el-form-item label="配图（可选）" prop="imageUrl">
              <el-input
                v-model="form.imageUrl"
                clearable
                :prefix-icon="Picture"
                placeholder="粘贴一张图片地址，https:// 开头"
                data-testid="review-image"
              />
              <div class="field-hint">演示环境使用外链图片；不填则只展示文字评价。</div>
            </el-form-item>

            <el-alert
              v-if="submitError"
              class="submit-error"
              type="error"
              :closable="false"
              show-icon
              :title="submitError"
            />

            <div class="form-actions">
              <el-button size="large" @click="backToShop">取消</el-button>
              <el-button
                type="primary"
                size="large"
                :icon="Promotion"
                :loading="submitting"
                data-testid="review-submit"
                @click="submit"
              >
                发布评价
              </el-button>
            </div>
            <p class="form-note">
              发布后可在门店详情评价区查看；评价会参与门店热度计算，请勿发布与消费无关的内容。
            </p>
          </el-form>
        </section>

        <aside class="review-aside">
          <div class="aside-card">
            <span class="aside-icon"><el-icon><StarFilled /></el-icon></span>
            <h3>写一条好评价</h3>
            <ul>
              <li>评分真实反映体验，1 星到 5 星都可表达。</li>
              <li>写清楚环境、服务与性价比，正文 10~500 字。</li>
              <li>配图请使用可公开访问的图片地址。</li>
            </ul>
          </div>
          <div v-if="shop" class="aside-card soft">
            <span class="aside-initial">{{ shopInitial }}</span>
            <p>{{ shop.name }}</p>
            <span>{{ Number(shop.comments || 0) }} 条评价 · 已售 {{ Number(shop.sold || 0) }}</span>
          </div>
        </aside>
      </div>
    </StateBlock>
  </div>
</template>

<style scoped>
.review-page {
  width: 100%;
}

.review-top {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  color: var(--faint);
  font-size: 12px;
}

.review-top :deep(.el-button) {
  color: var(--muted);
}

.crumb {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 282px;
  align-items: start;
  gap: 20px;
}

.review-card {
  min-width: 0;
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.shop-strip {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px;
  margin-bottom: 20px;
  border: 1px solid #eef5ff;
  border-radius: 13px;
  background: var(--surface-soft);
}

.shop-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 11px;
  background: var(--brand-50);
  color: var(--brand-500);
  font-size: 18px;
}

.shop-strip-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.45;
}

.shop-strip-info b {
  overflow: hidden;
  color: var(--ink);
  font-size: 14px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-strip-info span {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  overflow: hidden;
  color: var(--muted);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shop-strip-info .el-icon {
  flex: none;
  color: var(--brand-400);
}

.shop-price {
  flex: none;
  color: var(--brand-600);
  font-size: 12px;
  font-weight: 700;
}

.review-form :deep(.el-form-item__label) {
  color: var(--ink-2);
  font-size: 12.5px;
  font-weight: 650;
}

.score-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.score-row :deep(.el-rate) {
  height: 32px;
}

.score-row :deep(.el-rate__icon) {
  margin-right: 5px;
  font-size: 26px;
}

.score-text {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--faint);
  font-size: 12px;
}

.score-text.filled {
  color: var(--gold);
  font-weight: 650;
}

.score-text b {
  font-size: 13px;
}

.content-hint {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  width: 100%;
  margin-top: 5px;
  color: var(--faint);
  font-size: 10.5px;
  line-height: 1.5;
}

.content-hint .warn {
  color: #b45309;
}

.hint-tip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.field-hint {
  margin-top: 5px;
  color: var(--faint);
  font-size: 10.5px;
  line-height: 1.5;
}

.submit-error {
  margin-bottom: 12px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}

.form-actions :deep(.el-button) {
  min-width: 112px;
}

.form-note {
  margin: 12px 0 0;
  color: var(--faint);
  font-size: 10.5px;
  line-height: 1.6;
}

.review-aside {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.aside-card {
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}

.aside-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: #fff7ed;
  color: var(--gold);
  font-size: 17px;
}

.aside-card h3 {
  margin: 10px 0 0;
  color: var(--ink);
  font-size: 14.5px;
  font-weight: 750;
}

.aside-card ul {
  margin: 9px 0 0;
  padding-left: 17px;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.8;
}

.aside-card.soft {
  text-align: center;
  background: linear-gradient(150deg, #eff6ff, #f8fbff);
  border-color: var(--brand-100);
}

.aside-initial {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-500), #93c5fd);
  color: #fff;
  font-size: 20px;
  font-weight: 850;
}

.aside-card.soft p {
  margin: 8px 0 2px;
  overflow: hidden;
  color: var(--ink-2);
  font-size: 12.5px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.aside-card.soft > span:last-child {
  color: var(--faint);
  font-size: 10.5px;
}

@media (max-width: 1024px) {
  .review-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .review-aside {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .review-card {
    padding: 16px;
    border-radius: var(--radius);
  }

  .review-aside {
    grid-template-columns: minmax(0, 1fr);
  }

  .shop-strip {
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 8px;
  }

  .shop-price {
    width: 100%;
    padding-left: 49px;
  }

  .score-row :deep(.el-rate__icon) {
    margin-right: 3px;
    font-size: 23px;
  }

  .form-actions {
    flex-direction: column-reverse;
  }

  .form-actions :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }
}
</style>
