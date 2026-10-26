import axios, { AxiosHeaders } from 'axios'
import type { AxiosError, AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import JSONBig from 'json-bigint'
import { clearSession, getAccessToken, getRefreshToken, saveTokens } from '@/utils/session'

export const API_BASE_URL = '/api'

/**
 * 全局 JSON 解析器：把所有超过 JS 安全整数范围的整数（Long id）保留为字符串。
 *
 * 后端多数 Long 字段已由 JsonConfig 序列化成字符串，但仍有接口（实测 /pay-orders 申请支付单、
 * 部分运行镜像的订单/支付单 id）会以 JSON number 返回 19 位雪花号。
 * 浏览器原生 JSON.parse 会把 2114674488525242369 舍入成 2114674488525242400，
 * 再拿这个 id 去查询/取消/退款就是“订单不存在”。这里统一用 json-bigint 解析，
 * 从源头保证所有 id 在进入业务代码前就是字符串。
 */
const JSONBigParser = JSONBig({ storeAsString: true })

function parseResponseData(data: unknown): unknown {
  if (typeof data !== 'string' || data.length === 0) {
    return data
  }
  try {
    return JSONBigParser.parse(data) as unknown
  } catch {
    // 非 JSON 响应（空体、纯文本错误页等）原样返回
    return data
  }
}

/**
 * 当前后端网关按“裸 JWT”读取 authorization（AuthGlobalFilter#firstHeader 不做 Bearer 前缀剥离），
 * 因此默认发送裸 token。若网关升级为标准 Bearer，构建时设置 VITE_AUTH_SCHEME=bearer 即可。
 */
const authScheme = String(import.meta.env.VITE_AUTH_SCHEME || 'raw').toLowerCase()

function authorizationValue(token: string): string {
  return authScheme === 'bearer' ? `Bearer ${token}` : token
}

interface RetryableConfig extends InternalAxiosRequestConfig {
  /** 已用新令牌重放过一次，避免刷新后仍 401 时无限循环 */
  _retried?: boolean
  /** 匿名接口：不附加 authorization */
  skipAuth?: boolean
  /** 登录等接口：401 不触发刷新 */
  skipAuthRefresh?: boolean
}

interface RefreshResult {
  accessToken: string
  refreshToken: string
}

const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 20000,
  // 统一在响应进入业务代码前完成大整数保护，所有 GET/POST 都生效
  transformResponse: [parseResponseData],
})

http.interceptors.request.use((config) => {
  const requestConfig = config as RetryableConfig
  if (!requestConfig.skipAuth) {
    const token = getAccessToken()
    if (token) {
      const headers = AxiosHeaders.from(requestConfig.headers)
      headers.set('authorization', authorizationValue(token))
      requestConfig.headers = headers
    }
  }
  return requestConfig
})

let refreshPromise: Promise<string> | null = null
let redirecting = false

/**
 * 并发 401 只刷新一次：所有请求共享同一个 refreshPromise。
 * 刷新成功后轮换 refreshToken（后端会立即作废旧 refresh），并返回新的 accessToken。
 */
function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

async function performRefresh(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new Error('缺少刷新令牌')
  }
  const headers: Record<string, string> = {
    // 后端从 x-refresh-token 请求头读取 refresh token（RefreshTokenDTO 亦支持请求体）
    'X-Refresh-Token': refreshToken,
  }
  const currentAccessToken = getAccessToken()
  if (currentAccessToken) {
    // 刷新接口本身也在网关鉴权范围内：带上旧 access 才能进入网关的“过期令牌无感刷新”分支
    headers.authorization = authorizationValue(currentAccessToken)
  }
  const { data } = await axios.post<RefreshResult>(
    `${API_BASE_URL}/users/token/refresh`,
    null,
    { headers, timeout: 15000 },
  )
  if (!data || typeof data.accessToken !== 'string' || !data.accessToken) {
    throw new Error('刷新令牌失败')
  }
  saveTokens(data.accessToken, data.refreshToken || refreshToken)
  return data.accessToken
}

function handleSessionExpired(): void {
  clearSession()
  if (window.location.pathname === '/login') {
    return
  }
  if (redirecting) return
  redirecting = true
  const redirect = encodeURIComponent(`${window.location.pathname}${window.location.search}`)
  // 用整页跳转确保 Pinia 里的旧登录态一并清空
  window.location.replace(`/login?redirect=${redirect}`)
}

http.interceptors.response.use(
  (response) => {
    // 网关在 access 过期但 refresh 有效时会无感换发，并通过响应头回传新 access
    const renewed = response.headers['x-access-token']
    if (typeof renewed === 'string' && renewed && renewed !== getAccessToken()) {
      saveTokens(renewed)
    }
    return response
  },
  async (error: AxiosError) => {
    const config = error.config as RetryableConfig | undefined
    if (error.response?.status !== 401 || !config) {
      return Promise.reject(error)
    }
    if (config.skipAuthRefresh) {
      return Promise.reject(error)
    }
    if (config._retried) {
      handleSessionExpired()
      return Promise.reject(error)
    }

    config._retried = true
    try {
      const accessToken = await refreshAccessToken()
      const headers = AxiosHeaders.from(config.headers)
      headers.set('authorization', authorizationValue(accessToken))
      config.headers = headers
      // 重放原请求；既可能是 GET，也可能是 POST（body 仍在 config.data 中）
      return await http.request<unknown, AxiosResponse<unknown>>(config)
    } catch {
      handleSessionExpired()
      return Promise.reject(error)
    }
  },
)

interface ErrorPayload {
  msg?: string
  message?: string
}

interface HttpErrorLike {
  response?: {
    data?: unknown
    status?: number
    headers?: Record<string, unknown>
  }
  code?: string
  message?: string
}

/**
 * 统一把后端业务错误、网关 401、网络异常翻译成可直接展示的中文文案。
 * 后端约定业务错误放在响应体 msg 字段（见 AuthGlobalFilter / 全局异常处理器）。
 */
export function getErrorMessage(error: unknown, fallback = '操作失败，请稍后重试'): string {
  const err = error as HttpErrorLike
  const data = err?.response?.data
  if (typeof data === 'string' && data.trim()) {
    // 非 JSON 响应或解析失败时，错误体以原文形式返回
    return data.trim()
  }
  if (data && typeof data === 'object') {
    const payload = data as ErrorPayload
    if (typeof payload.msg === 'string' && payload.msg) return payload.msg
    if (typeof payload.message === 'string' && payload.message) return payload.message
  }
  if (!err?.response) {
    // 本地抛出的业务错误（例如登录响应缺字段）优先展示自身文案；
    // Axios 的网络异常统一翻译成“网关未启动”，避免把 "Network Error" 直接甩给用户。
    if (err?.message && err.message !== 'Network Error') {
      return err.message
    }
    return '暂时无法连接后端服务，请确认网关 http://localhost:8080 已启动'
  }
  if (err.code === 'ECONNABORTED') {
    return '请求超时，请稍后重试'
  }
  return err.message || fallback
}

export function getAuthErrorReason(error: unknown): string {
  const err = error as HttpErrorLike
  const value = err?.response?.headers?.['x-auth-error']
  return typeof value === 'string' ? value : ''
}

/** api/modules.ts 可用的扩展请求配置 */
export interface RequestOptions extends AxiosRequestConfig {
  skipAuth?: boolean
  skipAuthRefresh?: boolean
}

/** 带自定义请求标记的 GET/POST 包装，供 api/modules.ts 复用 */
export function get<T>(url: string, config?: RequestOptions): Promise<T> {
  return http.get<T>(url, config).then((response) => response.data)
}

export function post<T>(url: string, data?: unknown, config?: RequestOptions): Promise<T> {
  return http.post<T>(url, data, config).then((response) => response.data)
}

export function put<T>(url: string, data?: unknown, config?: RequestOptions): Promise<T> {
  return http.put<T>(url, data, config).then((response) => response.data)
}

/** DELETE 包装：员工管理删除接口用；与 get/post/put 共用 401 刷新与错误翻译 */
export function del<T>(url: string, config?: RequestOptions): Promise<T> {
  return http.delete<T>(url, config).then((response) => response.data)
}

export default http
