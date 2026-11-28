/**
 * 设备列表的筛选条件 ↔ 地址栏查询串（刷新、返回、分享链接时保留筛选）。
 * 不依赖 Vue，可在 Node 里直接单测。
 */
export interface DeviceFilters {
  sn: string
  imei: string
  phone: string
  bindStatus: '' | 'PENDING' | 'BOUND'
  onlineState: '' | 'ONLINE' | 'SLEEP' | 'OFFLINE'
  /** '' 不限 */
  enabled: '' | 'true' | 'false'
  page: number
}

export const EMPTY_FILTERS: DeviceFilters = {
  sn: '',
  imei: '',
  phone: '',
  bindStatus: '',
  onlineState: '',
  enabled: '',
  page: 1,
}

const BIND = ['PENDING', 'BOUND'] as const
const ONLINE = ['ONLINE', 'SLEEP', 'OFFLINE'] as const

type Query = Record<string, unknown>

function str(v: unknown): string {
  const first = Array.isArray(v) ? v[0] : v
  return typeof first === 'string' ? first.trim() : ''
}

/** 地址栏 → 筛选条件：不认识的值丢掉，不报错 */
export function fromQuery(q: Query): DeviceFilters {
  const bind = str(q.bindStatus)
  const online = str(q.onlineState)
  const enabled = str(q.enabled)
  const page = Number(str(q.page))
  return {
    sn: str(q.sn),
    imei: str(q.imei),
    phone: str(q.phone),
    bindStatus: (BIND as readonly string[]).includes(bind)
      ? (bind as DeviceFilters['bindStatus'])
      : '',
    onlineState: (ONLINE as readonly string[]).includes(online)
      ? (online as DeviceFilters['onlineState'])
      : '',
    enabled: enabled === 'true' || enabled === 'false' ? enabled : '',
    page: Number.isInteger(page) && page >= 1 ? page : 1,
  }
}

/** 筛选条件 → 地址栏：空值不写，第 1 页不写 */
export function toQuery(f: DeviceFilters): Record<string, string> {
  const q: Record<string, string> = {}
  for (const key of ['sn', 'imei', 'phone', 'bindStatus', 'onlineState', 'enabled'] as const) {
    const v = f[key].trim()
    if (v) q[key] = v
  }
  if (f.page > 1) q.page = String(f.page)
  return q
}

/** 筛选条件 → 接口参数：SN 转大写（前缀匹配，服务端不区分大小写，统一更直观） */
export function toParams(
  f: DeviceFilters,
  size: number,
): Record<string, string | number | boolean> {
  const p: Record<string, string | number | boolean> = { page: f.page, size }
  const sn = f.sn.replace(/\s+/g, '').toUpperCase()
  const imei = f.imei.replace(/\s+/g, '')
  const phone = f.phone.replace(/\s+/g, '')
  if (sn) p.sn = sn
  if (imei) p.imei = imei
  if (phone) p.phone = phone
  if (f.bindStatus) p.bindStatus = f.bindStatus
  if (f.onlineState) p.onlineState = f.onlineState
  if (f.enabled) p.enabled = f.enabled === 'true'
  return p
}

/** 提交前检查：SN、IMEI 只接受字母数字（前缀匹配）；手机号须完整 11 位（精确匹配，服务端不做模糊查询） */
export function filterProblem(f: DeviceFilters): string | null {
  if (f.sn && !/^[A-Za-z0-9]+$/.test(f.sn.replace(/\s+/g, ''))) return 'SN 只能包含字母和数字'
  if (f.imei && !/^\d+$/.test(f.imei.replace(/\s+/g, ''))) return 'IMEI 只能包含数字'
  if (f.phone && !/^1\d{10}$/.test(f.phone.replace(/\s+/g, ''))) return '手机号需输入完整 11 位'
  return null
}
