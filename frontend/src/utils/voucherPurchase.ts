import type { Voucher, VoucherPurchase } from '../api/types'

export function isOrdinaryVoucher(voucher: Pick<Voucher, 'remainingStock' | 'beginTime' | 'endTime' | 'inSeckillTime'>): boolean {
  return voucher.remainingStock == null && voucher.beginTime == null
    && voucher.endTime == null && voucher.inSeckillTime == null
}

export type PurchaseResult = {
  state: VoucherPurchase['state'] | 'EMPTY' | 'UNKNOWN' | 'DISMISSED'
  userVoucherId?: string
}

interface PurchaseOptions {
  lock?: <T>(name: string, work: () => Promise<T>) => Promise<T>
  storage: () => Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
  currentUser: () => string | undefined
  newRequestId: () => string
  query: (requestId: string) => Promise<VoucherPurchase>
  receive: (voucherId: string, pw: string, requestId: string) => Promise<string>
  wait: () => Promise<void>
}

const requestPattern = /^[A-Za-z0-9_-]{16,64}$/

export function createVoucherPurchase(options: PurchaseOptions) {
  function assertUser(userId: string): void {
    if (!userId || options.currentUser() !== userId) throw new Error('登录账号已变化，请刷新后重试')
  }

  function storageKey(userId: string, voucherId: string): string {
    return `llmall:voucher-purchase:v1:${encodeURIComponent(userId)}:${encodeURIComponent(voucherId)}`
  }

  async function locked<T>(userId: string, voucherId: string, work: () => Promise<T>): Promise<T> {
    assertUser(userId)
    const name = storageKey(userId, voucherId)
    if (options.lock) return options.lock(name, work)
    if (!globalThis.navigator?.locks?.request) {
      throw new Error('浏览器不支持安全的跨标签页锁，已停止购买；请使用支持 Web Locks 的浏览器并通过 HTTPS 或 localhost 访问')
    }
    // 确认、落键、提交和终态清理共用一把跨页面锁；不提供无锁支付降级。
    return navigator.locks.request(name, { mode: 'exclusive' }, work)
  }

  function read(userId: string, voucherId: string): string | null {
    assertUser(userId)
    try {
      const value = options.storage().getItem(storageKey(userId, voucherId))
      if (value !== null && !requestPattern.test(value)) throw new Error('invalid key')
      return value
    } catch {
      throw new Error('无法读取购买记录，请恢复浏览器本地存储后重试，勿清理未完成记录')
    }
  }

  function clear(userId: string, voucherId: string, requestId: string): void {
    // 清理失败保留原键，下次继续查询终态。
    try {
      if (read(userId, voucherId) === requestId) options.storage().removeItem(storageKey(userId, voucherId))
    } catch { /* 已确认的终态仍可展示 */ }
  }

  async function resolve(userId: string, voucherId: string, requestId: string): Promise<PurchaseResult> {
    try {
      for (let index = 0; index < 5; index++) {
        assertUser(userId)
        const status = await options.query(requestId)
        assertUser(userId)
        if (status.requestId !== requestId) return { state: 'UNKNOWN' }
        if (status.state === 'NOT_FOUND') {
          return status.voucherId === null && status.amount === null && status.userVoucherId === null
            ? { state: 'NOT_FOUND' } : { state: 'UNKNOWN' }
        }
        if (status.voucherId !== voucherId) return { state: 'UNKNOWN' }
        switch (status.state) {
          case 'ISSUED':
            if (!status.userVoucherId || typeof status.userVoucherId !== 'string') return { state: 'UNKNOWN' }
            clear(userId, voucherId, requestId)
            return { state: 'ISSUED', userVoucherId: status.userVoucherId }
          case 'CANCELED':
            clear(userId, voucherId, requestId)
            return { state: 'CANCELED' }
          case 'ACTIVE':
          case 'CANCELING':
            if (index === 4) return { state: status.state }
            await options.wait()
            break
          default: return { state: 'UNKNOWN' }
        }
      }
    } catch { /* 查询失败不能证明未扣款，保留原键 */ }
    return { state: 'UNKNOWN' }
  }

  async function restoreLocked(userId: string, voucherId: string): Promise<PurchaseResult> {
    const requestId = read(userId, voucherId)
    return requestId ? resolve(userId, voucherId, requestId) : { state: 'EMPTY' }
  }

  async function purchaseLocked(userId: string, voucherId: string, confirm: () => Promise<string | undefined>): Promise<PurchaseResult> {
    const previous = read(userId, voucherId)
    if (previous) {
      const result = await resolve(userId, voucherId, previous)
      if (result.state !== 'NOT_FOUND') return result
    }
    const pw = await confirm()
    if (pw === undefined) return { state: 'DISMISSED' }
    assertUser(userId)
    if (read(userId, voucherId) !== previous) throw new Error('购买记录已变化，请重试查询')
    const requestId = previous || options.newRequestId()
    if (!requestPattern.test(requestId)) throw new Error('无法生成安全购买请求，请更新浏览器')
    try {
      options.storage().setItem(storageKey(userId, voucherId), requestId)
      if (read(userId, voucherId) !== requestId) throw new Error('write not persisted')
    } catch {
      throw new Error('无法保存购买记录，已停止支付，请恢复浏览器本地存储后重试')
    }
    assertUser(userId)
    try {
      const userVoucherId = await options.receive(voucherId, pw, requestId)
      assertUser(userId)
      if (typeof userVoucherId !== 'string' || !userVoucherId) return { state: 'UNKNOWN' }
      clear(userId, voucherId, requestId)
      return { state: 'ISSUED', userVoucherId }
    } catch {
      return resolve(userId, voucherId, requestId)
    }
  }
  return {
    purchase: (userId: string, voucherId: string, confirm: () => Promise<string | undefined>) =>
      locked(userId, voucherId, () => purchaseLocked(userId, voucherId, confirm)),
    restore: (userId: string, voucherId: string) =>
      locked(userId, voucherId, () => restoreLocked(userId, voucherId)),
  }
}
