import { ApiError, ErrorCode } from '../api/errors'
import type { DeviceStatus } from '../api/types'

/**
 * 扫码绑定（MP-05，PRD 5.2、6.1 第 4–5 页）的纯逻辑：从二维码取 SN、激活码输入、预检与绑定结果、等待联网。
 * 页面在 pages/bind，规则说明见 docs/biz/bind.md。
 */

export const BIND_PAGE = '/pages/bind/bind'
export const RESULT_PAGE = '/pages/bind/result'

/** 与契约 common.yaml#/Sn 一致 */
const SN_RE = /^[A-Z0-9]{8,32}$/

/** SN 不区分大小写、忽略空白（docs/biz/bind.md） */
export function normalizeSn(raw: string): string | null {
  const sn = raw.replace(/\s+/g, '').toUpperCase()
  return SN_RE.test(sn) ? sn : null
}

/**
 * 从扫码结果取 SN。机身二维码的内容由服务端 `smartparking.device.qr-url-template` 决定（必须含 `{sn}`，
 * 正式域名与路径待 EXT-13），所以这里不认域名，只取链接里的 `sn` 参数；也接受直接是 SN 的二维码。
 */
export function parseScan(raw: string | null | undefined): string | null {
  const text = raw?.trim()
  if (!text) return null
  if (!text.includes('://')) return normalizeSn(text)
  // 只看查询串里的 sn=，不依赖 URL 解析（小程序没有 URL 类）
  const query = text.split('#')[0].split('?')[1]
  if (!query) return null
  for (const pair of query.split('&')) {
    const eq = pair.indexOf('=')
    const key = eq < 0 ? pair : pair.slice(0, eq)
    if (key.toLowerCase() !== 'sn') continue
    try {
      return normalizeSn(decodeURIComponent(eq < 0 ? '' : pair.slice(eq + 1)))
    } catch {
      return null
    }
  }
  return null
}

/**
 * 绑定页的 SN 来源：
 * - 小程序内扫码后跳转：`?sn=`
 * - 微信扫机身二维码直接打开（“扫普通链接二维码打开小程序”）：`?q=` 为 encodeURIComponent 过的完整链接
 */
export function snFromLaunch(query: Record<string, string | undefined>): string | null {
  if (query.sn) return normalizeSn(query.sn)
  if (query.q) {
    try {
      return parseScan(decodeURIComponent(query.q))
    } catch {
      return null
    }
  }
  return null
}

export function bindPageUrl(sn: string): string {
  return `${BIND_PAGE}?sn=${encodeURIComponent(sn)}`
}

// ------------------------------------------------------------ 激活码

export const CODE_LENGTH = 8

/** 输入框的值：去空白、转大写、只留字母与数字，最多 8 位（包装上的激活码可能带空格分组） */
export function normalizeCode(input: string): string {
  return input
    .toUpperCase()
    .replace(/[^0-9A-Z]/g, '')
    .slice(0, CODE_LENGTH)
}

export function codeComplete(code: string): boolean {
  return /^[0-9A-Z]{8}$/.test(code)
}

// ------------------------------------------------------------ 预检与绑定

export type BindState = 'BINDABLE' | 'BOUND_BY_ME' | 'BOUND_BY_OTHER' | 'DISABLED'

export interface BindPreview {
  sn: string
  model: string
  state: BindState
  deviceId?: number | null
}

/**
 * 绑定页显示什么：
 * - input：输入激活码（含锁定中，输入框禁用）
 * - mine：本人已是主人，直接进入控制页
 * - taken：已有主人，给客服入口（持购买凭证申诉）
 * - disabled：设备已停用，给客服入口
 * - not-found：SN 不存在，可能扫错了码
 */
export type BindView = 'input' | 'mine' | 'taken' | 'disabled' | 'not-found'

export function previewView(preview: BindPreview): BindView {
  switch (preview.state) {
    case 'BINDABLE':
      return 'input'
    case 'BOUND_BY_ME':
      return preview.deviceId ? 'mine' : 'taken'
    case 'BOUND_BY_OTHER':
      return 'taken'
    default:
      return 'disabled'
  }
}

export const VIEW_TEXT: Record<Exclude<BindView, 'input' | 'mine'>, { title: string; desc: string }> = {
  taken: { title: '该设备已有主人', desc: '如果这是你购买的设备，请联系客服并准备好购买凭证，我们会协助处理。' },
  disabled: { title: '设备已停用', desc: '该设备已被停用，暂时无法绑定，请联系客服。' },
  'not-found': { title: '未找到该设备', desc: '请确认扫描的是车位锁机身上的二维码。' },
}

/**
 * 绑定失败后怎么办：
 * - view：切到对应的页面状态（已被他人绑定、已停用、不存在）
 * - locked：错误次数过多，输入框禁用，提示多久后再试
 * - need-phone：服务端要求先授权手机号（前端已先检查，兜底）
 * - retry：激活码错误（带剩余次数）或网络等，就地提示，可再试
 */
export type BindFailure =
  | { kind: 'view'; view: BindView }
  | { kind: 'locked'; message: string }
  | { kind: 'need-phone' }
  | { kind: 'retry'; message: string }

export function bindFailure(err: ApiError): BindFailure {
  switch (err.code) {
    case ErrorCode.DEVICE_ALREADY_BOUND:
      return { kind: 'view', view: 'taken' }
    case ErrorCode.DEVICE_DISABLED:
      return { kind: 'view', view: 'disabled' }
    case ErrorCode.DEVICE_NOT_FOUND:
      return { kind: 'view', view: 'not-found' }
    case ErrorCode.ACTIVATE_LOCKED:
      return { kind: 'locked', message: err.message }
    case ErrorCode.BAD_REQUEST:
      return err.message.includes('手机号') ? { kind: 'need-phone' } : { kind: 'retry', message: err.message }
    default:
      return { kind: 'retry', message: err.message }
  }
}

// ------------------------------------------------------------ 等待联网

/** 绑定后等待设备联网的时长（PRD 5.2⑥），超过仍算绑定成功 */
export const CONNECT_WAIT_MS = 60_000
/** 等待期间查询状态的间隔 */
export const CONNECT_POLL_MS = 2000
/** 超时后继续以较慢的间隔查询，联网了仍可转为成功 */
export const CONNECT_SLOW_POLL_MS = 5000
/**
 * 判定“联网成功”时容忍的手机与服务器时钟偏差。
 * 契约说“绑定后首次 statusRefreshedAt 非空即联网成功”；但解绑后重新绑定的设备可能还留着之前的刷新时刻，
 * 所以只认绑定前后这个窗口内的刷新。
 */
export const CLOCK_SKEW_MS = 120_000

export type Connection = 'waiting' | 'connected' | 'timeout'

/**
 * @param boundAt 绑定成功的时刻（手机时钟）
 * @param now 当前时刻（手机时钟）
 */
export function connection(status: Pick<DeviceStatus, 'statusRefreshedAt'> | null, boundAt: number, now: number): Connection {
  const refreshed = status?.statusRefreshedAt ? Date.parse(status.statusRefreshedAt) : NaN
  if (!Number.isNaN(refreshed) && refreshed >= boundAt - CLOCK_SKEW_MS) return 'connected'
  return now - boundAt >= CONNECT_WAIT_MS ? 'timeout' : 'waiting'
}

// ------------------------------------------------------------ 名称与车位号

export const NAME_MAX = 32
