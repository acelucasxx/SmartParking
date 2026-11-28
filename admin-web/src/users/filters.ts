/**
 * 用户管理（ADM-07）的筛选条件 ↔ 地址栏与接口参数。规则见 docs/biz/admin-queries.md“C 端用户”：
 * 手机号须完整 11 位（HMAC 精确匹配，不做模糊查询），按状态筛选，按注册时间倒序。不依赖 Vue，可在 Node 里直接单测。
 */
export interface UserFilters {
  phone: string
  status: '' | 'ACTIVE' | 'BANNED'
  page: number
}

export const EMPTY_USER_FILTERS: UserFilters = { phone: '', status: '', page: 1 }

function str(v: unknown): string {
  const first = Array.isArray(v) ? v[0] : v
  return typeof first === 'string' ? first.trim() : ''
}

export function fromQuery(q: Record<string, unknown>): UserFilters {
  const status = str(q.status)
  const page = Number(str(q.page))
  return {
    phone: str(q.phone),
    status: status === 'ACTIVE' || status === 'BANNED' ? status : '',
    page: Number.isInteger(page) && page >= 1 ? page : 1,
  }
}

export function toQuery(f: UserFilters): Record<string, string> {
  const q: Record<string, string> = {}
  const phone = f.phone.replace(/\s+/g, '')
  if (phone) q.phone = phone
  if (f.status) q.status = f.status
  if (f.page > 1) q.page = String(f.page)
  return q
}

export function toParams(f: UserFilters, size: number): Record<string, string | number> {
  const p: Record<string, string | number> = { page: f.page, size }
  const phone = f.phone.replace(/\s+/g, '')
  if (phone) p.phone = phone
  if (f.status) p.status = f.status
  return p
}

export function filterProblem(f: UserFilters): string | null {
  const phone = f.phone.replace(/\s+/g, '')
  if (phone && !/^1\d{10}$/.test(phone)) return '手机号需输入完整 11 位（只能精确查询）'
  return null
}

/** 封禁 / 解封弹窗里的后果说明（docs/biz/disable-ban.md） */
export const BAN_EFFECT =
  '封禁后该用户登录和全部小程序功能都被拒绝（提示“账号已停用”）；不解绑设备，其名下设备的被授权人在有效期内仍可使用；其发出未被接受的邀请与转让卡片暂时不可接受，解封后在有效期内恢复。'
export const UNBAN_EFFECT = '解封后立即恢复，绑定与授权关系不变。'
