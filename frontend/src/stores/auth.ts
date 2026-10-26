import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/modules'
import type { LoginPayload, LoginResult, UserProfile } from '@/api/types'
import {
  clearSession,
  getAccessToken,
  getRefreshToken,
  getStoredUser,
  saveSession,
  saveUser,
} from '@/utils/session'

function toProfile(data: UserProfile): UserProfile {
  return {
    userId: data.userId,
    username: data.username,
    nickName: data.nickName,
    icon: data.icon ?? null,
    balance: Number(data.balance ?? 0),
    credits: Number(data.credits ?? 0),
    level: data.level ?? null,
    needChangePayPassword: Boolean(data.needChangePayPassword),
    needChangeLoginPassword: Boolean(data.needChangeLoginPassword),
    needChangePassword: Boolean(data.needChangePassword),
  }
}

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(getAccessToken())
  const refreshToken = ref(getRefreshToken())
  const user = ref<UserProfile | null>(getStoredUser())
  const loggingIn = ref(false)
  const loggingOut = ref(false)

  const isLoggedIn = computed(() => Boolean(accessToken.value))
  const displayName = computed(() => user.value?.nickName || user.value?.username || '未登录')
  const balance = computed(() => Number(user.value?.balance ?? 0))
  const credits = computed(() => Number(user.value?.credits ?? 0))
  const avatarText = computed(() => {
    const name = (user.value?.nickName || user.value?.username || 'L').trim()
    return name.charAt(0).toUpperCase()
  })

  function applyLogin(result: LoginResult): void {
    const token = result.accessToken || result.token || ''
    if (!token) {
      throw new Error('登录响应缺少 accessToken')
    }
    accessToken.value = token
    refreshToken.value = result.refreshToken || ''
    const profile = toProfile(result)
    user.value = profile
    saveSession(token, refreshToken.value, profile)
  }

  async function login(payload: LoginPayload): Promise<LoginResult> {
    loggingIn.value = true
    try {
      const result = await authApi.login(payload)
      applyLogin(result)
      return result
    } finally {
      loggingIn.value = false
    }
  }

  async function fetchMe(): Promise<UserProfile> {
    // 刷新令牌后 storage 里的 accessToken 可能已更新，进入页面时同步一次
    const latestToken = getAccessToken()
    if (latestToken) {
      accessToken.value = latestToken
      refreshToken.value = getRefreshToken()
    }
    const profile = toProfile(await authApi.me())
    user.value = profile
    saveUser(profile)
    return profile
  }

  async function logout(): Promise<void> {
    loggingOut.value = true
    try {
      await authApi.logout()
    } catch {
      // 网络异常或令牌已失效时，本地仍必须退出，避免用户卡在“退不出去”的状态
    } finally {
      reset()
      loggingOut.value = false
    }
  }

  function reset(): void {
    accessToken.value = ''
    refreshToken.value = ''
    user.value = null
    clearSession()
  }

  return {
    accessToken,
    refreshToken,
    user,
    loggingIn,
    loggingOut,
    isLoggedIn,
    displayName,
    balance,
    credits,
    avatarText,
    login,
    fetchMe,
    logout,
    reset,
  }
})
