import type { HttpRequest, HttpResponse, Method } from '../api/client'
import { METHOD_OVERRIDE_HEADER } from '../api/client'
import { ErrorCode, defaultMessage } from '../api/errors'

/** mock 接口抛出它表示业务错误，按统一信封返回 */
export class MockError extends Error {
  constructor(
    readonly code: number,
    message?: string,
    readonly status = 400,
  ) {
    super(message ?? defaultMessage(code))
  }
}

export interface MockContext {
  params: Record<string, string>
  query: Record<string, string>
  data: Record<string, unknown>
  /** Bearer 后面的 token；没带时为 null */
  token: string | null
}

export type MockHandler = (ctx: MockContext) => unknown

interface Route {
  method: Method
  pattern: RegExp
  keys: string[]
  handler: MockHandler
  auth: boolean
}

/** 极简路由：路径里 :name 为参数。与真实服务一致，除登录外都要求带 token */
export class MockRouter {
  private readonly routes: Route[] = []

  on(method: Method, path: string, handler: MockHandler, options: { auth?: boolean } = {}): this {
    const keys: string[] = []
    const source = path.replace(/:([a-zA-Z]+)/g, (_, key: string) => {
      keys.push(key)
      return '([^/]+)'
    })
    this.routes.push({ method, pattern: new RegExp(`^${source}$`), keys, handler, auth: options.auth !== false })
    return this
  }

  handle(request: HttpRequest): HttpResponse {
    // 与服务端一致：POST + X-HTTP-Method-Override: PATCH 按 PATCH 处理
    const method: Method =
      request.method === 'POST' && request.headers[METHOD_OVERRIDE_HEADER]?.toUpperCase() === 'PATCH'
        ? 'PATCH'
        : request.method
    const path = request.path.split('?')[0]
    for (const route of this.routes) {
      if (route.method !== method) continue
      const match = route.pattern.exec(path)
      if (!match) continue
      const token = bearer(request.headers.Authorization)
      if (route.auth && !token) return fail(ErrorCode.UNAUTHORIZED, undefined, 401)
      const params: Record<string, string> = {}
      route.keys.forEach((key, i) => (params[key] = decodeURIComponent(match[i + 1])))
      const query: Record<string, string> = {}
      for (const [key, value] of Object.entries(request.query ?? {})) {
        if (value !== null && value !== undefined) query[key] = String(value)
      }
      const data = request.data && typeof request.data === 'object' ? (request.data as Record<string, unknown>) : {}
      try {
        return { status: 200, body: { code: 0, message: '成功', data: route.handler({ params, query, data, token }) ?? null } }
      } catch (e) {
        if (e instanceof MockError) return fail(e.code, e.message, e.status)
        throw e
      }
    }
    return fail(ErrorCode.NOT_FOUND, `mock 未实现：${method} ${path}`, 404)
  }
}

function bearer(header: string | undefined): string | null {
  if (!header || !header.startsWith('Bearer ')) return null
  return header.slice('Bearer '.length).trim() || null
}

function fail(code: number, message: string | undefined, status: number): HttpResponse {
  return { status, body: { code, message: message ?? defaultMessage(code) } }
}
