import { ErrorCode } from '../api/errors'
import type { DateTime } from '../api/types'
import { validity } from '../utils/format'

/**
 * 成员管理与邀请家人（MP-06，PRD 5.5、6.1 第 6–7 页）的纯逻辑：有效期选项与自定义时间校验、名额、文案。
 * 规则说明见 docs/biz/invites.md、members.md。
 */

/** 契约 common.yaml#/Period */
export type Period =
  | { type: 'PERMANENT' }
  | { type: 'RELATIVE'; durationDays: 1 | 7 | 30 }
  | { type: 'CUSTOM'; validFrom: DateTime; validTo: DateTime }

export interface Member {
  id: number
  nickname: string
  remark?: string | null
  validFrom: DateTime
  validTo?: DateTime | null
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'QUIT'
}

export interface PendingInvite {
  id: number
  remark?: string | null
  period: Period
  expireAt: DateTime
  createdAt: DateTime
}

export interface MemberOverview {
  limit: number
  used: number
  members: Member[]
  invites: PendingInvite[]
}

/** 契约 ShareCard：token 只出现这一次 */
export interface ShareCard {
  id: number
  token: string
  expireAt: DateTime
  path: string
  title: string
}

// ------------------------------------------------------------ 时间（东八区，与手机时区无关）

const OFFSET = 8 * 3_600_000
const MINUTE = 60_000
const DAY = 86_400_000
const pad = (n: number) => String(n).padStart(2, '0')

/** 时间选择器用的日期与时刻：{ date: '2026-12-01', time: '08:00' } */
export function pickerParts(ms: number): { date: string; time: string } {
  const d = new Date(ms + OFFSET)
  return {
    date: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`,
    time: `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`,
  }
}

/** 选择器的日期 + 时刻 → 东八区 ISO；格式不对为 null */
export function fromPicker(date: string, time: string): DateTime | null {
  // 不同 JS 引擎对非标准日期格式的解析不一致，先按选择器的格式拦下（Node 下与 Date.parse 结果相同，测不出差别）
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null
  const iso = `${date}T${time}:00+08:00`
  return Number.isNaN(Date.parse(iso)) ? null : iso
}

/** 12-01 08:00（今年）/ 2027-01-01 08:00（往年） */
export function shortDateTime(iso: string, now: number): string {
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return iso
  const p = pickerParts(ms)
  const sameYear = p.date.slice(0, 4) === pickerParts(now).date.slice(0, 4)
  return `${sameYear ? p.date.slice(5) : p.date} ${p.time}`
}

// ------------------------------------------------------------ 有效期选项

export type PeriodChoice = 'PERMANENT' | 'DAY_1' | 'DAY_7' | 'DAY_30' | 'CUSTOM'

/** PRD 5.5②：永久 / 1 天 / 7 天 / 30 天 / 自定义起止（精确到分钟） */
export const PERIOD_OPTIONS: Array<{ value: PeriodChoice; label: string }> = [
  { value: 'PERMANENT', label: '永久' },
  { value: 'DAY_1', label: '1 天' },
  { value: 'DAY_7', label: '7 天' },
  { value: 'DAY_30', label: '30 天' },
  { value: 'CUSTOM', label: '自定义' },
]

export interface CustomRange {
  fromDate: string
  fromTime: string
  toDate: string
  toTime: string
}

/** 自定义时间的默认值：从现在（取整到下一分钟）到 7 天后 */
export function defaultRange(now: number): CustomRange {
  const start = Math.ceil(now / MINUTE) * MINUTE
  const from = pickerParts(start)
  const to = pickerParts(start + 7 * DAY)
  return { fromDate: from.date, fromTime: from.time, toDate: to.date, toTime: to.time }
}

/**
 * 选项 → 请求里的 period。自定义时与服务端规则一致（按分钟、止晚于起、止晚于当前时间；起可以早于当前时间）。
 * @returns 合法时 `{ ok: true, period }`，否则提示
 */
export function buildPeriod(choice: PeriodChoice, range: CustomRange, now: number): { ok: true; period: Period } | { ok: false; message: string } {
  switch (choice) {
    case 'PERMANENT':
      return { ok: true, period: { type: 'PERMANENT' } }
    case 'DAY_1':
      return { ok: true, period: { type: 'RELATIVE', durationDays: 1 } }
    case 'DAY_7':
      return { ok: true, period: { type: 'RELATIVE', durationDays: 7 } }
    case 'DAY_30':
      return { ok: true, period: { type: 'RELATIVE', durationDays: 30 } }
  }
  const validFrom = fromPicker(range.fromDate, range.fromTime)
  const validTo = fromPicker(range.toDate, range.toTime)
  if (!validFrom || !validTo) return { ok: false, message: '请选择开始和结束时间' }
  if (Date.parse(validTo) <= Date.parse(validFrom)) return { ok: false, message: '结束时间需晚于开始时间' }
  // PRD 6.1 第 7 页异常态：自定义时间早于当前
  if (Date.parse(validTo) <= now) return { ok: false, message: '结束时间需晚于当前时间' }
  return { ok: true, period: { type: 'CUSTOM', validFrom, validTo } }
}

/** 修改成员有效期时的初始选项：永久的选“永久”，其余按现有起止预填“自定义” */
export function choiceForMember(member: Pick<Member, 'validFrom' | 'validTo'>, now: number): { choice: PeriodChoice; range: CustomRange } {
  if (!member.validTo) return { choice: 'PERMANENT', range: defaultRange(now) }
  const from = pickerParts(Date.parse(member.validFrom))
  const to = pickerParts(Date.parse(member.validTo))
  return { choice: 'CUSTOM', range: { fromDate: from.date, fromTime: from.time, toDate: to.date, toTime: to.time } }
}

/** 邀请里的有效期说明：永久 / 7 天（接受后起算）/ 12-01 08:00 至 12-31 18:00 */
export function periodText(period: Period, now: number): string {
  switch (period.type) {
    case 'PERMANENT':
      return '永久'
    case 'RELATIVE':
      return `${period.durationDays} 天（接受后起算）`
    default:
      return `${shortDateTime(period.validFrom, now)} 至 ${shortDateTime(period.validTo, now)}`
  }
}

/** 成员的有效期：尚未开始的显示开始时间，其余沿用首页口径（永久有效 / 有效期至… / 剩余 X 小时） */
export function memberValidity(member: Pick<Member, 'validFrom' | 'validTo'>, now: number): string {
  const from = Date.parse(member.validFrom)
  if (!Number.isNaN(from) && from > now) {
    const until = member.validTo ? ` 至 ${shortDateTime(member.validTo, now)}` : '，永久'
    return `${shortDateTime(member.validFrom, now)} 起生效${until}`
  }
  return validity(member.validTo, now)
}

// ------------------------------------------------------------ 名额与备注

export interface Quota {
  /** 已用 2 / 5 */
  text: string
  full: boolean
  /** 名额已满时的说明 */
  tip: string
}

/** 名额 = 生效成员 + 待接受邀请（PRD 5.5③），满了“邀请家人”置灰并提示（PRD 5.5①） */
export function quota(overview: Pick<MemberOverview, 'used' | 'limit'>): Quota {
  const full = overview.used >= overview.limit
  return {
    text: `已用 ${overview.used} / ${overview.limit}`,
    full,
    tip: full ? `名额已满（最多 ${overview.limit} 人，含待接受的邀请），撤销成员或撤回邀请后可再邀请` : '',
  }
}

export const REMARK_MAX = 32

export function remarkProblem(remark: string): string | null {
  return remark.trim().length > REMARK_MAX ? `备注最多 ${REMARK_MAX} 个字` : null
}

/**
 * 发邀请、改有效期失败后怎么办：
 * - refresh：名额变了，或成员 / 邀请已结束，提示后回成员页刷新
 * - leave：已不是主人、设备不存在，提示后回首页
 * - show：就地提示（有效期不合法、停用等）
 */
export function memberFailure(code: number): 'refresh' | 'leave' | 'show' {
  switch (code) {
    // 名额变了；成员已结束（撤销、到期、退出）或邀请已失效（10004）
    case ErrorCode.MEMBER_LIMIT_REACHED:
    case ErrorCode.NOT_FOUND:
      return 'refresh'
    case ErrorCode.NOT_OWNER:
    case ErrorCode.DEVICE_NOT_FOUND:
      return 'leave'
    default:
      return 'show'
  }
}

/** 分享卡片：标题与路径都用服务端给的（路径里带一次性 token） */
export function shareMessage(card: Pick<ShareCard, 'title' | 'path'>): { title: string; path: string } {
  return { title: card.title, path: card.path }
}
