import { dayRange } from '../utils/format.ts'

/**
 * 指令审计（ADM-04）的筛选条件 ↔ 地址栏、接口参数与导出检查。
 * 规则见 docs/biz/admin-queries.md“指令审计”：SN 精确匹配（不区分大小写），操作人手机号完整 11 位；
 * 导出必须给时间范围，跨度不超过 31 天，且导出接口不支持按手机号筛选。不依赖 Vue，可在 Node 里直接单测。
 */
export interface CommandFilters {
  deviceId: string
  sn: string
  phone: string
  channel: '' | 'REMOTE' | 'BLE'
  status: '' | 'PENDING' | 'SENT' | 'EXECUTING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT'
  /** 东八区自然日 YYYY-MM-DD，起止都含 */
  from: string
  to: string
  page: number
}

export const EMPTY: CommandFilters = {
  deviceId: '',
  sn: '',
  phone: '',
  channel: '',
  status: '',
  from: '',
  to: '',
  page: 1,
}

const CHANNELS = ['REMOTE', 'BLE']
const STATUSES = ['PENDING', 'SENT', 'EXECUTING', 'SUCCESS', 'FAILED', 'TIMEOUT']
const DATE = /^\d{4}-\d{2}-\d{2}$/

function str(v: unknown): string {
  const first = Array.isArray(v) ? v[0] : v
  return typeof first === 'string' ? first.trim() : ''
}

/** 地址栏 → 筛选条件：不认识的值丢掉 */
export function fromQuery(q: Record<string, unknown>): CommandFilters {
  const channel = str(q.channel)
  const status = str(q.status)
  const deviceId = str(q.deviceId)
  const from = str(q.from)
  const to = str(q.to)
  const page = Number(str(q.page))
  return {
    deviceId: /^[1-9]\d*$/.test(deviceId) ? deviceId : '',
    sn: str(q.sn),
    phone: str(q.phone),
    channel: CHANNELS.includes(channel) ? (channel as CommandFilters['channel']) : '',
    status: STATUSES.includes(status) ? (status as CommandFilters['status']) : '',
    // 起止要成对出现
    from: DATE.test(from) && DATE.test(to) ? from : '',
    to: DATE.test(from) && DATE.test(to) ? to : '',
    page: Number.isInteger(page) && page >= 1 ? page : 1,
  }
}

/** 筛选条件 → 地址栏：空值与第 1 页不写 */
export function toQuery(f: CommandFilters): Record<string, string> {
  const q: Record<string, string> = {}
  for (const key of ['deviceId', 'sn', 'phone', 'channel', 'status', 'from', 'to'] as const) {
    const v = f[key].trim()
    if (v) q[key] = v
  }
  if (f.page > 1) q.page = String(f.page)
  return q
}

/** 共同的筛选参数（列表与导出都用）；SN 转大写，日期换算为 from（含）/ to（不含） */
function common(f: CommandFilters): Record<string, string | number> {
  const p: Record<string, string | number> = {}
  if (f.deviceId) p.deviceId = Number(f.deviceId)
  const sn = f.sn.replace(/\s+/g, '').toUpperCase()
  if (sn) p.sn = sn
  if (f.channel) p.channel = f.channel
  if (f.status) p.status = f.status
  // 起止缺一时 dayRange 返回 null
  const range = dayRange([f.from, f.to])
  if (range) Object.assign(p, range)
  return p
}

export function listParams(f: CommandFilters, size: number): Record<string, string | number> {
  const p: Record<string, string | number> = { ...common(f), page: f.page, size }
  const phone = f.phone.replace(/\s+/g, '')
  if (phone) p.phone = phone
  return p
}

/** 提交筛选前检查 */
export function filterProblem(f: CommandFilters): string | null {
  if (f.sn && !/^[A-Za-z0-9]+$/.test(f.sn.replace(/\s+/g, ''))) return 'SN 只能包含字母和数字'
  if (f.phone && !/^1\d{10}$/.test(f.phone.replace(/\s+/g, '')))
    return '操作人手机号需输入完整 11 位'
  return null
}

export const EXPORT_MAX_DAYS = 31

/**
 * 导出参数：必须选时间范围且跨度不超过 31 天（起止日都含）；导出接口不支持按操作人手机号筛选。
 * 结果超过 10 万行由服务端拒绝（10001）。
 */
export function exportParams(
  f: CommandFilters,
): { ok: true; params: Record<string, string | number> } | { ok: false; message: string } {
  if (f.phone) return { ok: false, message: '导出不支持按操作人手机号筛选，请清空手机号后导出' }
  if (!f.from || !f.to) return { ok: false, message: '导出须先选择时间范围（不超过 31 天）' }
  // 起止都含；反着选也按跨度算（dayRange 会把起止排好）
  const days =
    Math.abs(Date.parse(`${f.to}T00:00:00Z`) - Date.parse(`${f.from}T00:00:00Z`)) / 86_400_000 + 1
  if (!(days <= EXPORT_MAX_DAYS))
    return { ok: false, message: `导出的时间范围不能超过 ${EXPORT_MAX_DAYS} 天` }
  return { ok: true, params: common(f) }
}
