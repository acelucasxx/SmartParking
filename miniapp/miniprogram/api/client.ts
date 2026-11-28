import { ApiError, ErrorCode } from './errors'
import type { LoginResult, UserProfile } from './types'

/**
 * 请求核心：会话、登录、信封解析、登录失效重登。不直接调用 wx.*，依赖由调用方注入，方便在 Node 里测试
 * （真实接入见 api/index.ts，mock 见 mock/transport.ts）。
 */

export type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface HttpRequest {
  method: Method
  /** 以 / 开头，不含 /api/app 前缀，如 /devices/1 */
  path: string
  query?: Record<string, string | number | boolean | null | undefined>
  data?: unknown
  headers: Record<string, string>
}

export interface HttpResponse {
  status: number
  body: unknown
}

/** 发出请求；网络不通、超时时 reject */
export type Transport = (request: HttpRequest) => Promise<HttpResponse>

/** 本地持久化，对应 wx.getStorageSync / setStorageSync / removeStorageSync */
export interface KeyValueStore {
  get(key: string): unknown
  set(key: string, value: unknown): void
  remove(key: string): void
}

export interface Session {
  token: string
  /** 毫秒时间戳 */
  expiresAt: number
  user: UserProfile
}

export interface ClientOptions {
  transport: Transport
  store: KeyValueStore
  /** wx.login 取临时 code */
  loginCode: () => Promise<string>
  now?: () => number
  /** 账号已停用（10010）时回调一次，页面据此进入停用提示 */
  onBanned?: () => void
}

export interface RequestOptions {
  query?: HttpRequest['query']
  data?: unknown
  /** 默认 true：先确保已登录，并带上 token */
  auth?: boolean
}

export const SESSION_KEY = 'sp_session'
/** 会话到期前这么久就视为失效、重新登录，避免请求途中过期 */
export const EXPIRY_MARGIN_MS = 60_000

/** 小程序 wx.request 不支持 PATCH：用 POST 加这个头，服务端按 PATCH 处理 */
export const METHOD_OVERRIDE_HEADER = 'X-HTTP-Method-Override'

export class ApiClient {
  private readonly now: () => number
  private loggingIn: Promise<Session> | null = null

  constructor(private readonly options: ClientOptions) {
    this.now = options.now ?? Date.now
  }

  /** 当前会话；没有或已过期时为 null */
  session(): Session | null {
    const raw = this.options.store.get(SESSION_KEY) as Session | undefined
    if (!raw || typeof raw !== 'object' || !raw.token) return null
    // expiresAt 不是数字时比较结果为 false，同样视为无效
    return raw.expiresAt - EXPIRY_MARGIN_MS > this.now() ? raw : null
  }

  /** 已有有效会话直接返回，否则登录；并发调用共用同一次登录 */
  ensureLogin(): Promise<Session> {
    const current = this.session()
    return current ? Promise.resolve(current) : this.login()
  }

  /** 重新登录（wx.login → /auth/login）；并发调用共用同一次登录 */
  login(): Promise<Session> {
    if (!this.loggingIn) {
      this.loggingIn = this.doLogin().finally(() => {
        this.loggingIn = null
      })
    }
    return this.loggingIn
  }

  /** 更新本地缓存的用户资料（如授权手机号后） */
  updateUser(user: UserProfile): void {
    const current = this.session()
    if (current) this.options.store.set(SESSION_KEY, { ...current, user })
  }

  /** 退出登录：通知服务端作废 token（失败也不影响本地退出），清掉本地会话 */
  async logout(): Promise<void> {
    if (this.session()) {
      try {
        await this.send('POST', '/auth/logout', {}, this.session())
      } catch {
        // 网络不通时仍然本地退出；服务端会话到期自然失效
      }
    }
    this.options.store.remove(SESSION_KEY)
  }

  request<T>(method: Method, path: string, options: RequestOptions = {}): Promise<T> {
    return options.auth === false ? this.send<T>(method, path, options, null) : this.authed<T>(method, path, options)
  }

  get<T>(path: string, query?: RequestOptions['query']): Promise<T> {
    return this.request<T>('GET', path, { query })
  }

  post<T>(path: string, data?: unknown): Promise<T> {
    return this.request<T>('POST', path, { data })
  }

  patch<T>(path: string, data?: unknown): Promise<T> {
    return this.request<T>('PATCH', path, { data })
  }

  delete<T>(path: string, data?: unknown): Promise<T> {
    return this.request<T>('DELETE', path, { data })
  }

  // ------------------------------------------------------------ 内部

  private async authed<T>(method: Method, path: string, options: RequestOptions): Promise<T> {
    const session = await this.ensureLogin()
    try {
      return await this.send<T>(method, path, options, session)
    } catch (e) {
      // 登录失效（服务端重启、被挤下线等）：重新登录后重试一次
      if (e instanceof ApiError && e.code === ErrorCode.UNAUTHORIZED) {
        this.options.store.remove(SESSION_KEY)
        const fresh = await this.login()
        return this.send<T>(method, path, options, fresh)
      }
      throw e
    }
  }

  private async doLogin(): Promise<Session> {
    let code: string
    try {
      code = await this.options.loginCode()
    } catch {
      throw new ApiError(ErrorCode.WX_LOGIN_FAILED)
    }
    const result = await this.send<LoginResult>('POST', '/auth/login', { data: { code } }, null)
    const expiresAt = Date.parse(result.expiresAt)
    const session: Session = {
      token: result.token,
      expiresAt: Number.isNaN(expiresAt) ? this.now() : expiresAt,
      user: result.user,
    }
    this.options.store.set(SESSION_KEY, session)
    return session
  }

  private async send<T>(method: Method, path: string, options: RequestOptions, session: Session | null): Promise<T> {
    const headers: Record<string, string> = {}
    if (session) headers.Authorization = `Bearer ${session.token}`
    let wireMethod = method
    if (method === 'PATCH') {
      wireMethod = 'POST'
      headers[METHOD_OVERRIDE_HEADER] = 'PATCH'
    }
    let response: HttpResponse
    try {
      response = await this.options.transport({
        method: wireMethod,
        path,
        query: options.query,
        data: options.data,
        headers,
      })
    } catch {
      throw new ApiError(ErrorCode.NETWORK)
    }
    return this.unwrap<T>(response)
  }

  private unwrap<T>(response: HttpResponse): T {
    const body = response.body as { code?: unknown; message?: unknown; data?: unknown } | null
    if (!body || typeof body !== 'object' || typeof body.code !== 'number') {
      // 网关等返回的非信封响应：401 仍按登录失效处理
      if (response.status === 401) throw new ApiError(ErrorCode.UNAUTHORIZED, undefined, 401)
      throw new ApiError(ErrorCode.BAD_RESPONSE, undefined, response.status)
    }
    if (body.code === ErrorCode.OK) return (body.data ?? null) as T
    const message = typeof body.message === 'string' ? body.message : undefined
    const error = new ApiError(body.code, message, response.status)
    if (error.code === ErrorCode.USER_BANNED) this.options.onBanned?.()
    throw error
  }
}

/** 拼查询串；跳过 null / undefined */
export function buildQuery(query?: HttpRequest['query']): string {
  if (!query) return ''
  const parts: string[] = []
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined) continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}
