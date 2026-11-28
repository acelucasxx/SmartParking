/**
 * 后台接口的错误（docs/api/README.md 错误码表）。注意 40001“用户名或密码错误”的 HTTP 状态也是 401，
 * 不能凭 401 判断“登录已过期”，要看信封里的 code。
 */
export const AdminCode = {
  OK: 0,
  /** 网络不通、超时：本地产生 */
  NETWORK: -1,
  /** 响应不是统一信封：本地产生 */
  BAD_RESPONSE: -2,
  BAD_REQUEST: 10001,
  UNAUTHORIZED: 10002,
  FORBIDDEN: 10003,
  NOT_FOUND: 10004,
  LOGIN_FAILED: 40001,
  ACCOUNT_LOCKED: 40002,
  ACCOUNT_DISABLED: 40003,
} as const

export class AdminApiError extends Error {
  readonly code: number
  readonly status: number

  constructor(code: number, message: string, status = 0) {
    super(message)
    this.name = 'AdminApiError'
    this.code = code
    this.status = status
  }
}

/** 服务端说“请先修改密码”时的提示（mustChangePassword 期间除少数接口外都返回 10003 加这句） */
export const MUST_CHANGE_PASSWORD_MESSAGE = '请先修改密码'

/**
 * 出错后全局该做什么：
 * - expired：会话失效（10002），清掉登录态回登录页
 * - change-password：仍在用临时密码，去修改密码页
 * - show：其余就地提示（包括登录失败、锁定、停用，由登录页显示）
 */
export type ErrorEffect = 'expired' | 'change-password' | 'show'

export function errorEffect(err: AdminApiError): ErrorEffect {
  if (err.code === AdminCode.UNAUTHORIZED) return 'expired'
  if (err.code === AdminCode.FORBIDDEN && err.message.includes(MUST_CHANGE_PASSWORD_MESSAGE))
    return 'change-password'
  return 'show'
}

export type Outcome<T> = { ok: true; data: T } | { ok: false; error: AdminApiError }

/**
 * 解开统一信封：code 为 0 即成功（不论 HTTP 状态），其余统一成 AdminApiError。
 * @param status HTTP 状态；网络失败时为 0
 * @param body 响应体；网络失败时为 undefined
 */
export function unwrap<T>(status: number, body: unknown): Outcome<T> {
  if (status !== 0 && isEnvelope(body) && body.code === AdminCode.OK)
    return { ok: true, data: (body.data ?? null) as T }
  return { ok: false, error: toError(status, body) }
}

function toError(status: number, body: unknown): AdminApiError {
  if (status === 0) return new AdminApiError(AdminCode.NETWORK, '网络异常，请稍后重试', 0)
  if (isEnvelope(body)) {
    return new AdminApiError(body.code, body.message?.trim() || '操作失败，请稍后重试', status)
  }
  // 网关返回的 401 不是信封（如会话被代理拦下）：按登录失效处理
  if (status === 401)
    return new AdminApiError(AdminCode.UNAUTHORIZED, '登录已过期，请重新登录', status)
  return new AdminApiError(AdminCode.BAD_RESPONSE, '服务异常，请稍后重试', status)
}

export function isEnvelope(
  body: unknown,
): body is { code: number; message?: string; data?: unknown } {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as { code?: unknown }).code === 'number'
  )
}

/** 登录失败的展示：锁定、停用用醒目的提示框，密码错误显示在表单下（含剩余次数） */
export function loginFailure(err: AdminApiError): {
  kind: 'locked' | 'disabled' | 'wrong' | 'other'
  message: string
} {
  switch (err.code) {
    case AdminCode.ACCOUNT_LOCKED:
      return { kind: 'locked', message: err.message }
    case AdminCode.ACCOUNT_DISABLED:
      return { kind: 'disabled', message: err.message }
    case AdminCode.LOGIN_FAILED:
      return { kind: 'wrong', message: err.message }
    default:
      return { kind: 'other', message: err.message }
  }
}
