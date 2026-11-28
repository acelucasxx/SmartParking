/**
 * 告警中心（ADM-05）的筛选条件 ↔ 地址栏、接口参数、处理备注检查与处理失败的判定。
 * 规则见 docs/biz/alarms.md“告警中心接口”。不依赖 Vue，可在 Node 里直接单测。
 */
export type StatusTab = 'OPEN' | 'HANDLED' | 'ALL'
export type AlarmType =
  '' | 'MOTOR_FAULT' | 'OBSTRUCTED' | 'INFRARED' | 'CMD_TIMEOUT' | 'OFFLINE_24H'

export interface AlarmFilters {
  /** 默认只看未处理的 */
  status: StatusTab
  type: AlarmType
  sn: string
  deviceId: string
  page: number
}

export const DEFAULT_FILTERS: AlarmFilters = {
  status: 'OPEN',
  type: '',
  sn: '',
  deviceId: '',
  page: 1,
}

const TYPES = ['MOTOR_FAULT', 'OBSTRUCTED', 'INFRARED', 'CMD_TIMEOUT', 'OFFLINE_24H']

function str(v: unknown): string {
  const first = Array.isArray(v) ? v[0] : v
  return typeof first === 'string' ? first.trim() : ''
}

export function fromQuery(q: Record<string, unknown>): AlarmFilters {
  const status = str(q.status)
  const type = str(q.type)
  const deviceId = str(q.deviceId)
  const page = Number(str(q.page))
  return {
    status: status === 'HANDLED' || status === 'ALL' ? status : 'OPEN',
    type: TYPES.includes(type) ? (type as AlarmType) : '',
    sn: str(q.sn),
    deviceId: /^[1-9]\d*$/.test(deviceId) ? deviceId : '',
    page: Number.isInteger(page) && page >= 1 ? page : 1,
  }
}

/** 默认值（未处理、第 1 页）不写进地址栏 */
export function toQuery(f: AlarmFilters): Record<string, string> {
  const q: Record<string, string> = {}
  if (f.status !== 'OPEN') q.status = f.status
  if (f.type) q.type = f.type
  if (f.sn.trim()) q.sn = f.sn.trim()
  if (f.deviceId) q.deviceId = f.deviceId
  if (f.page > 1) q.page = String(f.page)
  return q
}

/** 接口参数：“全部”不传 status；SN 去空白转大写（服务端不区分大小写） */
export function toParams(f: AlarmFilters, size: number): Record<string, string | number> {
  const p: Record<string, string | number> = { page: f.page, size }
  if (f.status !== 'ALL') p.status = f.status
  if (f.type) p.type = f.type
  const sn = f.sn.replace(/\s+/g, '').toUpperCase()
  if (sn) p.sn = sn
  if (f.deviceId) p.deviceId = Number(f.deviceId)
  return p
}

export const NOTE_MAX = 512

/** 处理备注：去首尾空白后 1–512 个字符（按字符计，与服务端一致） */
export function noteProblem(note: string): string | null {
  const n = note.trim()
  if (!n) return '请填写处理说明'
  if (Array.from(n).length > NOTE_MAX) return `处理说明最多 ${NOTE_MAX} 个字`
  return null
}

/**
 * 标记处理失败：
 * - refresh：已被别人处理（10009）或告警不存在（10004），提示后刷新列表
 * - show：其余在弹窗里提示，可再试
 */
export function handleFailure(code: number): 'refresh' | 'show' {
  return code === 10009 || code === 10004 ? 'refresh' : 'show'
}
