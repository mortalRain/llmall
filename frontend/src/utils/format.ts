const currencyFormatter = new Intl.NumberFormat('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

function toNumber(value: unknown): number {
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

/** 分 → 元，带千分位与两位小数（账户余额等需要精确展示的场景） */
export function formatMoney(fen: unknown): string {
  return `¥${currencyFormatter.format(toNumber(fen) / 100)}`
}

/** 分 → 元，去掉无意义的 .00（商品价 / 券面额等卡片场景） */
export function formatPrice(fen: unknown): string {
  const yuan = toNumber(fen) / 100
  return `¥${Number.isInteger(yuan) ? yuan : yuan.toFixed(2)}`
}

/** 只有数字，不带 ¥（用于输入框前缀等） */
export function yuan(fen: unknown): string {
  return currencyFormatter.format(toNumber(fen) / 100)
}

export function formatCount(value: unknown): string {
  const num = toNumber(value)
  if (num >= 10000) {
    return `${(num / 10000).toFixed(1).replace(/\.0$/, '')}万`
  }
  return String(num)
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

interface LocalDateTimeArray extends Array<number> {
  0: number
  1: number
  2: number
  3: number
  4: number
  5: number
}

function parseDate(value: unknown): Date | null {
  if (!value) return null
  if (Array.isArray(value)) {
    const parts = value as LocalDateTimeArray
    if (parts.length < 3) return null
    return new Date(parts[0], parts[1] - 1, parts[2], parts[3] ?? 0, parts[4] ?? 0, parts[5] ?? 0)
  }
  const text = String(value).trim()
  if (!text) return null
  const normalized = text.includes('T') ? text : text.replace(' ', 'T')
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 后端 LocalDateTime → 2026-10-26 18:48 */
export function formatDateTime(value: unknown): string {
  const date = parseDate(value)
  if (!date) return '—'
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 只展示日期：2026-10-26 */
export function formatDate(value: unknown): string {
  const date = parseDate(value)
  if (!date) return '—'
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 动态流相对时间 */
export function formatRelativeTime(value: unknown): string {
  const date = parseDate(value)
  if (!date) return '—'
  const diff = Date.now() - date.getTime()
  if (diff < 0) return '刚刚'
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 7 * day) return `${Math.floor(diff / day)} 天前`
  return formatDate(value)
}

export function isExpired(value: unknown): boolean {
  const date = parseDate(value)
  return Boolean(date && date.getTime() <= Date.now())
}

export function splitImages(value: string | null | undefined): string[] {
  if (!value) return []
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

export function initialOf(name: string | null | undefined): string {
  const text = String(name ?? '').trim()
  return text ? text.charAt(0).toUpperCase() : 'L'
}

/**
 * 商品规格：后端 item.spec 是 JSON 字符串（如 {"颜色":"红色","尺码":"21寸"}），
 * 直接渲染会把大括号和引号一起展示。这里转成"颜色：红色 / 尺码：21寸"；
 * 非 JSON 的纯文本（如门店套餐的"到店核销 · 有效期30天"）原样返回。
 */
export function formatSpec(value: string | null | undefined): string {
  const raw = String(value ?? '').trim()
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return raw
    const entries = Object.entries(parsed)
      .filter(([, item]) => item !== null && item !== undefined && String(item).trim() !== '')
      .map(([key, item]) => `${key}：${String(item)}`)
    return entries.length ? entries.join(' / ') : ''
  } catch {
    return raw
  }
}

export function scoreOf(value: unknown): string {
  const num = toNumber(value)
  return num > 0 ? (num / 10).toFixed(1) : '—'
}

export function parseRules(rules: string | null | undefined): Array<[string, string]> {
  if (!rules) return []
  try {
    const parsed = JSON.parse(rules) as Record<string, unknown>
    return Object.entries(parsed).map(([key, value]) => [key, String(value)])
  } catch {
    return []
  }
}

export function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0
    const value = char === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}

export interface StatusMeta {
  text: string
  type: 'primary' | 'success' | 'warning' | 'info' | 'danger'
  hint: string
}

export function orderStatusMeta(status: number): StatusMeta {
  switch (Number(status)) {
    case 1:
      return { text: '待付款', type: 'warning', hint: '订单已创建，请在 15 分钟内完成支付' }
    case 2:
      return { text: '待使用', type: 'primary', hint: '已支付，到店出示核销码即可使用' }
    case 7:
      return { text: '已核销', type: 'success', hint: '门店已完成核销，交易完成' }
    case 5:
      return { text: '已关闭', type: 'info', hint: '订单已取消或已退款关闭' }
    default:
      return { text: `状态 ${status}`, type: 'info', hint: '' }
  }
}

export function voucherStatusMeta(status: number): StatusMeta {
  switch (Number(status)) {
    case 1:
      return { text: '未使用', type: 'success', hint: '到店消费时使用' }
    case 2:
      return { text: '已使用', type: 'info', hint: '已绑定订单使用' }
    case 3:
      return { text: '已过期', type: 'info', hint: '有效期已结束' }
    default:
      return { text: '未知', type: 'info', hint: '' }
  }
}

export function paymentTypeText(type: number): string {
  switch (Number(type)) {
    case 1:
      return '支付宝'
    case 2:
      return '微信支付'
    case 3:
      return '余额支付'
    default:
      return '其他方式'
  }
}
