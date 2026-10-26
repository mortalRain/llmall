import type { UserProfile } from '@/api/types'

const ACCESS_TOKEN_KEY = 'llmall.accessToken'
const REFRESH_TOKEN_KEY = 'llmall.refreshToken'
const USER_KEY = 'llmall.user'

function read(key: string): string {
  try {
    return window.localStorage.getItem(key) ?? ''
  } catch {
    return ''
  }
}

function write(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // 隐私模式下 localStorage 可能不可写：登录态仍保存在 Pinia 内存中
  }
}

function remove(key: string): void {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // ignore
  }
}

export function getAccessToken(): string {
  return read(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string {
  return read(REFRESH_TOKEN_KEY)
}

export function getStoredUser(): UserProfile | null {
  const raw = read(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as UserProfile
  } catch {
    return null
  }
}

/** 只更新令牌；不传 refreshToken 时保留原值（网关无感刷新只会回新 access）。 */
export function saveTokens(accessToken: string, refreshToken?: string): void {
  write(ACCESS_TOKEN_KEY, accessToken)
  if (refreshToken) {
    write(REFRESH_TOKEN_KEY, refreshToken)
  }
}

export function saveUser(user: UserProfile): void {
  write(USER_KEY, JSON.stringify(user))
}

export function saveSession(accessToken: string, refreshToken: string, user: UserProfile): void {
  saveTokens(accessToken, refreshToken)
  saveUser(user)
}

export function clearSession(): void {
  remove(ACCESS_TOKEN_KEY)
  remove(REFRESH_TOKEN_KEY)
  remove(USER_KEY)
}
