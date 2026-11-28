import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { type AdminApiError, errorEffect, unwrap } from '@/auth/errors'
import { filenameFromDisposition, isJsonResponse } from './files'

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

/** multipart 上传，字段名为 file */
export function upload<T>(url: string, file: File, options?: RequestOptions) {
  const form = new FormData()
  form.append('file', file)
  // 交给浏览器设置带 boundary 的 Content-Type
  return request<T>({ ...options, method: 'POST', url, data: form, timeout: 60_000 })
}

/**
 * 下载文件：带上登录态请求，成功时按 Content-Disposition 的文件名保存；
 * 出错时服务端返回的是 JSON 信封，按普通接口错误处理（提示、会话失效等）。
 */
export async function download(
  url: string,
  fallbackName: string,
  params?: Record<string, unknown>,
): Promise<void> {
  const token = hooks?.token()
  let resp
  try {
    resp = await http.request<Blob>({
      method: 'GET',
      url,
      params,
      responseType: 'blob',
      timeout: 120_000,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) {
      const body = await blobJson(e.response.data)
      const outcome = unwrap(e.response.status, body)
      if (!outcome.ok) return fail(outcome.error, false, !!token)
    }
    return fail((unwrap(0, undefined) as { ok: false; error: AdminApiError }).error, false, !!token)
  }
  const type = resp.headers['content-type'] as string | undefined
  if (isJsonResponse(type)) {
    const outcome = unwrap(resp.status, await blobJson(resp.data))
    if (!outcome.ok) return fail(outcome.error, false, !!token)
  }
  if (token) hooks?.onActivity()
  const name = filenameFromDisposition(
    resp.headers['content-disposition'] as string | undefined,
    fallbackName,
  )
  const href = URL.createObjectURL(resp.data)
  const a = document.createElement('a')
  a.href = href
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 给浏览器时间开始下载再释放
  setTimeout(() => URL.revokeObjectURL(href), 10_000)
}

async function blobJson(data: unknown): Promise<unknown> {
  if (!(data instanceof Blob)) return data
  try {
    return JSON.parse(await data.text())
  } catch {
    return undefined
  }
}
