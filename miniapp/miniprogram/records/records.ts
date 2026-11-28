import type { OperationRecord, Page } from '../api/types'
import { ACTION_LABEL, RESULT_MESSAGES, SOURCE_LABEL } from '../device/control'

/**
 * 操作记录（MP-10，PRD 6.1 第 11 页、8.7）的纯逻辑：按日分组、每条记录的展示、分页合并。
 * 规则说明见 docs/biz/records.md：主人看自成为主人起的全部（含遥控器、地感等非 App 触发），被授权人只看本人发起的。
 */

const OFFSET = 8 * 3_600_000
const DAY = 86_400_000
const pad = (n: number) => String(n).padStart(2, '0')
const WEEK = ['日', '一', '二', '三', '四', '五', '六']

/** 东八区的日期 YYYY-MM-DD（按日筛选的参数、分组的键） */
export function dayKey(ms: number): string {
  const d = new Date(ms + OFFSET)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}

/** 分组标题：今天 / 昨天 / 11-27 周五（今年）/ 2025-11-27 周四（往年） */
export function dayLabel(key: string, now: number): string {
  const ms = Date.parse(`${key}T00:00:00+08:00`)
  if (Number.isNaN(ms)) return key
  const today = dayKey(now)
  if (key === today) return '今天'
  if (key === dayKey(now - DAY)) return '昨天'
  const week = `周${WEEK[new Date(ms + OFFSET).getUTCDay()]}`
  return `${key.slice(0, 4) === today.slice(0, 4) ? key.slice(5) : key} ${week}`
}

export interface RecordRow {
  id: string
  /** 10:05，定时查询发现的前面加“约” */
  time: string
  /** 抬杆 / 落杆 */
  action: string
  /** 张先生 · 远程 / 遥控器 / 地感 / 雷达 */
  who: string
  /** 主人 / 家人；非 App 触发为空 */
  role: string
  result: string
  tone: 'success' | 'danger' | 'warning' | 'muted'
  /** 耗时 1.2 秒；非 App 或未终结为空 */
  duration: string
}

export function recordRow(r: OperationRecord): RecordRow {
  const d = new Date(Date.parse(r.at) + OFFSET)
  const hm = `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`
  const app = r.source === 'APP'
  const channel = r.channel === 'BLE' ? '蓝牙' : '远程'
  return {
    id: r.id,
    time: `${r.approx ? '约 ' : ''}${hm}`,
    action: ACTION_LABEL[r.action],
    who: app ? `${r.operatorName || '微信用户'} · ${channel}` : SOURCE_LABEL[r.source] || '现场操作',
    role: app ? (r.operatorRole === 'OWNER' ? '主人' : r.operatorRole === 'MEMBER' ? '家人' : '') : '',
    ...result(r),
    duration: app && r.durationMs !== null && r.durationMs !== undefined && r.durationMs > 0 ? `${(r.durationMs / 1000).toFixed(1)} 秒` : '',
  }
}

function result(r: OperationRecord): Pick<RecordRow, 'result' | 'tone'> {
  // 非 App 触发的动作只记到位，没有结果字段
  if (r.source !== 'APP') return { result: '', tone: 'muted' }
  switch (r.result) {
    case 'SUCCESS':
      return { result: r.resultCode === 'ALREADY_IN_TARGET' ? '已在目标状态' : '成功', tone: 'success' }
    case 'TIMEOUT':
      return { result: '超时', tone: 'warning' }
    case 'FAILED':
      return { result: (r.resultCode && RESULT_MESSAGES[r.resultCode].split('，')[0]) || '失败', tone: 'danger' }
    default:
      return { result: '执行中', tone: 'muted' }
  }
}

export interface Section {
  key: string
  label: string
  rows: RecordRow[]
}

/** 按东八区日期分组，保持接口的倒序 */
export function groupByDay(records: OperationRecord[], now: number): Section[] {
  const sections: Section[] = []
  for (const r of records) {
    const key = dayKey(Date.parse(r.at))
    let last = sections[sections.length - 1]
    if (!last || last.key !== key) {
      last = { key, label: dayLabel(key, now), rows: [] }
      sections.push(last)
    }
    last.rows.push(recordRow(r))
  }
  return sections
}

/**
 * 加载下一页后合并：按 id 去重（翻页期间有新记录时，后一页会出现前一页已有的记录）。
 */
export function mergePage(existing: OperationRecord[], next: OperationRecord[]): OperationRecord[] {
  const seen = new Set(existing.map((r) => r.id))
  return [...existing, ...next.filter((r) => !seen.has(r.id))]
}

/** 还有下一页 */
export function hasMore(page: Pick<Page<unknown>, 'page' | 'size' | 'total'>): boolean {
  return page.page * page.size < page.total
}

/** 空态文案：按日筛选时与全部不同；被授权人只看到本人的 */
export function emptyText(date: string, isOwner: boolean): string {
  if (date) return '这一天没有操作记录'
  return isOwner ? '还没有操作记录' : '你还没有操作过这台设备'
}
