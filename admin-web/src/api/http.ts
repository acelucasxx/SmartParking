import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { type AdminApiError, errorEffect, unwrap } from '@/auth/errors'

/** 与服务端 ApiResponse 对应。code 为 0 表示成功。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data?: T
}

export interface RequestOptions extends AxiosRequestConfig {
  /** 不弹全局错误提示，由调用方自己展示（如登录页） */
  silent?: boolean
}

/**
 * 登录态相关的回调，由 auth store 注册，避免 http 与 store 互相引用：
 * - token：当前 token
 * - onActivity：请求成功，顺延前端的空闲计时（服务端每次请求顺延空闲超时）
 * - onExpired：会话失效（10002）
 * - onMustChangePassword：仍在用临时密码（10003“请先修改密码”）
 */
export interface AuthHooks {
  token(): string | null
  onActivity(): void
  onExpired(message: string): void
  onMustChangePassword(): void
}

let hooks: AuthHooks | null = null

export function setAuthHooks(value: AuthHooks): void {
  hooks = value
}

const http = axios.create({ baseURL: '/api/admin', timeout: 10_000 })

/** 发请求并解开信封；失败统一抛 AdminApiError */
export async function request<T>(config: RequestOptions): Promise<T> {
  const { silent, ...axiosConfig } = config
  const token = hooks?.token()
  const headers = { ...(axiosConfig.headers as Record<string, string> | undefined) }
  if (token) headers.Authorization = `Bearer ${token}`
  let status = 0
  let body: unknown
  try {
    const resp = await http.request({ ...axiosConfig, headers })
    status = resp.status
    body = resp.data
  } catch (e) {
    // 网络失败时 status 保持 0
    if (axios.isAxiosError(e) && e.response) {
      status = e.response.status
      body = e.response.data
    }
  }
  const outcome = unwrap<T>(status, body)
  if (!outcome.ok) return fail(outcome.error, silent, !!token)
  if (token) hooks?.onActivity()
  return outcome.data
}

function fail(err: AdminApiError, silent: boolean | undefined, authed: boolean): never {
  const effect = errorEffect(err)
  // 带着 token 的请求才可能是“会话失效”；登录接口本身的 401（40001）不在这里处理
  if (effect === 'expired' && authed) hooks?.onExpired(err.message)
  else if (effect === 'change-password') hooks?.onMustChangePassword()
  else if (!silent) ElMessage.error(err.message)
  throw err
}

export const get = <T>(url: string, params?: unknown, options?: RequestOptions) =>
  request<T>({ ...options, method: 'GET', url, params })
export const post = <T>(url: string, data?: unknown, options?: RequestOptions) =>
  request<T>({ ...options, method: 'POST', url, data })
export const put = <T>(url: string, data?: unknown, options?: RequestOptions) =>
  request<T>({ ...options, method: 'PUT', url, data })
export const patch = <T>(url: string, data?: unknown, options?: RequestOptions) =>
  request<T>({ ...options, method: 'PATCH', url, data })
