<script setup lang="ts">
import { computed } from 'vue'
import type { UserVoucher, Voucher } from '@/api/types'
import { formatDate, formatPrice, parseRules, voucherStatusMeta } from '@/utils/format'

type VoucherLike = Voucher | UserVoucher

const props = withDefaults(
  defineProps<{
    voucher: VoucherLike
    mode?: 'shop' | 'mine'
    loading?: boolean
  }>(),
  {
    mode: 'shop',
    loading: false,
  },
)

const emit = defineEmits<{ (event: 'receive'): void }>()

const isReceived = computed(() => 'received' in props.voucher && Boolean(props.voucher.received))
const isPaid = computed(() => 'payValue' in props.voucher && Number(props.voucher.payValue) > 0)
const payValue = computed(() => ('payValue' in props.voucher ? Number(props.voucher.payValue) : 0))
const expireTime = computed(() => ('expireTime' in props.voucher ? props.voucher.expireTime : null))
const statusMeta = computed(() => voucherStatusMeta(Number(props.voucher.status)))
const inactive = computed(() => props.mode === 'mine' && Number(props.voucher.status) !== 1)
const ruleText = computed(() =>
  parseRules(props.voucher.rules)
    .slice(0, 2)
    .map(([key, value]) => `${key}：${value}`)
    .join(' · '),
)
const buttonLabel = computed(() => {
  if (isReceived.value) return '已领取'
  return isPaid.value ? `${formatPrice(payValue.value)} 购买` : '立即领取'
})
</script>

<template>
  <article class="voucher-card" :class="{ inactive }">
    <div class="voucher-value">
      <span class="value-main">{{ formatPrice(voucher.actualValue) }}</span>
      <span class="value-label">{{ isPaid ? '付费代金券' : '门店代金券' }}</span>
    </div>

    <div class="voucher-main">
      <div class="voucher-top">
        <h4>{{ voucher.title }}</h4>
        <el-tag v-if="mode === 'mine'" :type="statusMeta.type" effect="light" size="small">
          {{ statusMeta.text }}
        </el-tag>
        <span v-else-if="mode === 'shop' && voucher.subTitle" class="subtitle">{{ voucher.subTitle }}</span>
      </div>
      <p v-if="ruleText" class="rule">{{ ruleText }}</p>
      <p v-if="mode === 'mine' && expireTime" class="expire">
        有效期至 {{ formatDate(expireTime) }}
      </p>
      <p v-else-if="isPaid" class="pay-note">售价 {{ formatPrice(payValue) }}，领取时需支付口令</p>
    </div>

    <div class="voucher-action">
      <template v-if="mode === 'shop'">
        <el-button
          type="primary"
          plain
          size="small"
          :loading="loading"
          :disabled="isReceived"
          @click="emit('receive')"
        >
          {{ buttonLabel }}
        </el-button>
      </template>
      <span v-else class="status-dot" :class="`status-${voucher.status}`"></span>
    </div>
  </article>
</template>

<style scoped>
.voucher-card {
  position: relative;
  display: flex;
  align-items: stretch;
  min-width: 0;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: 0 4px 14px rgba(59, 130, 246, 0.05);
}

.voucher-card.inactive {
  opacity: 0.68;
  filter: saturate(0.65);
}

.voucher-value {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 112px;
  flex: none;
  padding: 14px 8px;
  background: linear-gradient(160deg, #eff6ff 0%, #dbeafe 100%);
  border-right: 1px dashed var(--brand-200);
  color: var(--brand-600);
}

.voucher-value::before,
.voucher-value::after {
  content: '';
  position: absolute;
  right: -7px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 1px solid var(--line);
}

.voucher-value::before {
  top: -8px;
}

.voucher-value::after {
  bottom: -8px;
}

.value-main {
  font-size: 25px;
  font-weight: 850;
  line-height: 1.1;
  letter-spacing: -0.04em;
}

.value-label {
  margin-top: 4px;
  color: var(--brand-500);
  font-size: 10.5px;
  font-weight: 600;
  white-space: nowrap;
}

.voucher-main {
  flex: 1;
  min-width: 0;
  padding: 13px 14px;
}

.voucher-top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

h4 {
  flex: 1;
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  font-size: 14.5px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.subtitle {
  flex: none;
  max-width: 45%;
  overflow: hidden;
  color: var(--muted);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule,
.expire,
.pay-note {
  margin: 5px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expire {
  color: var(--faint);
}

.pay-note {
  color: #b45309;
}

.voucher-action {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  padding: 0 14px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--faint);
}

.status-dot.status-1 {
  background: #22c55e;
  box-shadow: 0 0 0 4px rgba(34, 197, 94, 0.12);
}

@media (max-width: 640px) {
  .voucher-value {
    width: 92px;
  }

  .value-main {
    font-size: 21px;
  }

  .voucher-main {
    padding: 12px;
  }

  .voucher-action {
    padding: 0 10px;
  }

  .subtitle {
    display: none;
  }
}
</style>
