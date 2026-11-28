import type { Message, MessageType } from '../api/types'
import { formatTime } from '../utils/format'

/**
 * 消息中心（MP-11，PRD 6.1 第 13 页）的纯逻辑：消息展示、点开去向、未读角标、分页合并与本地标记已读。
 * 写入点与文案见 docs/biz/messages.md。
 */

export const TYPE_LABEL: Record<MessageType, { text: string; tone: 'info' | 'success' | 'warning' | 'danger' | 'muted' }> = {
  GRANT: { text: '授权', tone: 'success' },
  REVOKE: { text: '撤销', tone: 'warning' },
  EXPIRE: { text: '到期', tone: 'warning' },
  TRANSFER: { text: '转让', tone: 'info' },
  UNBIND: { text: '解绑', tone: 'muted' },
  FAULT: { text: '故障', tone: 'danger' },
  SYSTEM: { text: '系统', tone: 'info' },
}

export interface MessageRow {
  id: number
  label: string
  tone: string
  title: string
  content: string
  time: string
  unread: boolean
  /** 点开后进入的设备；没有为 0 */
  deviceId: number
}

/**
 * 点开消息后去哪：获得授权、设备故障、成为新主人这类设备仍可用的，进入控制页；
 * 撤销、到期、解绑、转让出去等设备已不可用的只标记已读。转让消息区分不了收件人身份，按标题判断是否成了新主人。
 */
export function targetDevice(m: Pick<Message, 'type' | 'deviceId' | 'title'>): number {
  if (!m.deviceId) return 0
  if (m.type === 'GRANT' || m.type === 'FAULT') return m.deviceId
  if (m.type === 'TRANSFER' && m.title.startsWith('你已成为')) return m.deviceId
  return 0
}

export function messageRow(m: Message, now: number): MessageRow {
  const label = TYPE_LABEL[m.type] ?? TYPE_LABEL.SYSTEM
  return {
    id: m.id,
    label: label.text,
    tone: label.tone,
    title: m.title,
    content: m.content,
    time: formatTime(m.createdAt, now),
    unread: !m.read,
    deviceId: targetDevice(m),
  }
}

/** tabBar 角标：0 不显示，超过 99 显示 99+ */
export function badgeText(count: number): string | null {
  if (!Number.isFinite(count) || count <= 0) return null
  return count > 99 ? '99+' : String(Math.floor(count))
}

/** 加载下一页后合并，按 id 去重（翻页期间有新消息时后一页会重复） */
export function mergeMessages(existing: Message[], next: Message[]): Message[] {
  const seen = new Set(existing.map((m) => m.id))
  return [...existing, ...next.filter((m) => !seen.has(m.id))]
}

/** 标记已读后先在本地更新，不等重新拉取；ids 为空表示全部 */
export function markRead(list: Message[], ids: number[] | 'all'): Message[] {
  return list.map((m) => (ids === 'all' || ids.includes(m.id) ? (m.read ? m : { ...m, read: true }) : m))
}
