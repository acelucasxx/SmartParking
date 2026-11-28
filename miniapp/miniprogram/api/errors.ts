/**
 * 错误码映射（docs/api/README.md 错误码表，只列小程序会遇到的 1xxxx–3xxxx）。
 * 服务端 message 是默认提示，具体场景会带补充信息（如“激活码错误，还可尝试 3 次”），展示时优先用服务端的。
 */

export const ErrorCode = {
  OK: 0,
  /** 网络不通、超时：小程序本地产生，不来自服务端 */
  NETWORK: -1,
  /** 响应不是统一信封：本地产生 */
  BAD_RESPONSE: -2,

  BAD_REQUEST: 10001,
  UNAUTHORIZED: 10002,
  FORBIDDEN: 10003,
  NOT_FOUND: 10004,
  CONFLICT: 10009,
  USER_BANNED: 10010,
  TOO_MANY_REQUESTS: 10011,
  WX_LOGIN_FAILED: 10020,
  WX_PHONE_FAILED: 10021,
  INTERNAL_ERROR: 19999,

  DEVICE_NOT_FOUND: 20001,
  DEVICE_DISABLED: 20002,
  DEVICE_OFFLINE: 20003,
  DEVICE_SLEEPING: 20004,
  COMMAND_IN_PROGRESS: 20005,
  DEVICE_FAULT: 20006,
  BLE_NOT_AVAILABLE: 20010,
  BLE_TICKET_INVALID: 20011,

  ACTIVATE_CODE_WRONG: 30001,
  ACTIVATE_LOCKED: 30002,
  DEVICE_ALREADY_BOUND: 30003,
  NOT_OWNER: 30004,
  ACCESS_EXPIRED: 30005,
  MEMBER_LIMIT_REACHED: 30010,
  INVALID_PERIOD: 30011,
  INVITE_EXPIRED: 30012,
  INVITE_USED: 30013,
  INVITE_CANCELLED: 30014,
  ALREADY_MEMBER: 30015,
  CANNOT_ACCEPT_OWN: 30016,
  TRANSFER_EXPIRED: 30020,
  TRANSFER_USED: 30021,
  TRANSFER_CANCELLED: 30022,
  TRANSFER_PENDING_EXISTS: 30023,
} as const

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode]

/** 服务端没给 message 时的默认提示 */
const DEFAULT_MESSAGES: Record<number, string> = {
  [ErrorCode.NETWORK]: '网络异常，请检查网络后重试',
  [ErrorCode.BAD_RESPONSE]: '服务异常，请稍后重试',
  [ErrorCode.BAD_REQUEST]: '请求参数不合法',
  [ErrorCode.UNAUTHORIZED]: '未登录或登录已过期',
  [ErrorCode.FORBIDDEN]: '无权执行该操作',
  [ErrorCode.NOT_FOUND]: '资源不存在',
  [ErrorCode.CONFLICT]: '状态冲突，请刷新后重试',
  [ErrorCode.USER_BANNED]: '账号已停用',
  [ErrorCode.TOO_MANY_REQUESTS]: '操作过于频繁，请稍后再试',
  [ErrorCode.WX_LOGIN_FAILED]: '微信登录失败，请重试',
  [ErrorCode.WX_PHONE_FAILED]: '获取手机号失败，请重试',
  [ErrorCode.INTERNAL_ERROR]: '系统繁忙，请稍后重试',
  [ErrorCode.DEVICE_NOT_FOUND]: '设备不存在',
  [ErrorCode.DEVICE_DISABLED]: '设备已停用，请联系客服',
  [ErrorCode.DEVICE_OFFLINE]: '设备离线，可在现场使用蓝牙',
  [ErrorCode.DEVICE_SLEEPING]: '设备暂时无法远程控制，可用蓝牙',
  [ErrorCode.COMMAND_IN_PROGRESS]: '设备正在执行上一条指令',
  [ErrorCode.DEVICE_FAULT]: '设备故障，请联系客服',
  [ErrorCode.BLE_NOT_AVAILABLE]: '蓝牙控制暂未开放',
  [ErrorCode.BLE_TICKET_INVALID]: '蓝牙票据无效或已过期',
  [ErrorCode.ACTIVATE_CODE_WRONG]: '激活码错误',
  [ErrorCode.ACTIVATE_LOCKED]: '错误次数过多，请 30 分钟后再试',
  [ErrorCode.DEVICE_ALREADY_BOUND]: '该设备已有主人',
  [ErrorCode.NOT_OWNER]: '仅主人可执行该操作',
  [ErrorCode.ACCESS_EXPIRED]: '授权已到期或已被撤销',
  [ErrorCode.MEMBER_LIMIT_REACHED]: '成员名额已满',
  [ErrorCode.INVALID_PERIOD]: '有效期不合法',
  [ErrorCode.INVITE_EXPIRED]: '邀请已过期',
  [ErrorCode.INVITE_USED]: '邀请已被他人接受',
  [ErrorCode.INVITE_CANCELLED]: '邀请已撤销',
  [ErrorCode.ALREADY_MEMBER]: '你已是该设备的成员',
  [ErrorCode.CANNOT_ACCEPT_OWN]: '不能接受自己发出的邀请或转让',
  [ErrorCode.TRANSFER_EXPIRED]: '转让卡片已过期',
  [ErrorCode.TRANSFER_USED]: '转让卡片已被他人接受',
  [ErrorCode.TRANSFER_CANCELLED]: '转让已取消',
  [ErrorCode.TRANSFER_PENDING_EXISTS]: '已有进行中的转让',
}

/**
 * 出错后页面该做什么：
 * - relogin：登录失效，请求层会自动重新登录并重试一次；仍失败才到页面
 * - banned：账号已停用，进入停用提示，只保留退出与联系客服
 * - leave：设备或授权已不可用（不存在、授权到期 / 撤销），提示后返回车位首页并刷新列表
 * - support：需要客服处理（停用、故障），提示并给出客服入口
 * - retry：网络或服务暂时异常，可重试
 * - show：其余业务错误，就地展示提示
 */
export type ErrorAction = 'relogin' | 'banned' | 'leave' | 'support' | 'retry' | 'show'

export function errorAction(code: number): ErrorAction {
  switch (code) {
    case ErrorCode.UNAUTHORIZED:
      return 'relogin'
    case ErrorCode.USER_BANNED:
      return 'banned'
    case ErrorCode.NOT_FOUND:
    case ErrorCode.DEVICE_NOT_FOUND:
    case ErrorCode.ACCESS_EXPIRED:
      return 'leave'
    case ErrorCode.DEVICE_DISABLED:
    case ErrorCode.DEVICE_FAULT:
      return 'support'
    case ErrorCode.NETWORK:
    case ErrorCode.BAD_RESPONSE:
    case ErrorCode.INTERNAL_ERROR:
    case ErrorCode.TOO_MANY_REQUESTS:
    case ErrorCode.WX_LOGIN_FAILED:
    case ErrorCode.WX_PHONE_FAILED:
      return 'retry'
    default:
      return 'show'
  }
}

export function defaultMessage(code: number): string {
  return DEFAULT_MESSAGES[code] ?? '操作失败，请稍后重试'
}

export class ApiError extends Error {
  readonly action: ErrorAction

  constructor(
    readonly code: number,
    message?: string,
    /** HTTP 状态码；网络异常时为 0 */
    readonly status = 0,
  ) {
    super(message && message.trim() ? message : defaultMessage(code))
    this.name = 'ApiError'
    this.action = errorAction(code)
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}
