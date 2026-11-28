import type { DeviceCard, GateStatus, OnlineState } from '../api/types'

/** 徽标色调，对应 app.wxss 里的 --sp-* 颜色 */
export type Tone = 'success' | 'info' | 'warning' | 'danger' | 'muted'

export interface Badge {
  label: string
  tone: Tone
}

/**
 * 设备在线徽标（PRD 8.5）。停用、故障优先于在线状态：这两种情况下远程与蓝牙都不可用，页面只给客服入口。
 */
export function onlineBadge(enabled: boolean, onlineState: OnlineState, gateStatus: GateStatus): Badge {
  if (!enabled) return { label: '已停用', tone: 'danger' }
  if (gateStatus === 'FAULT') return { label: '故障', tone: 'danger' }
  switch (onlineState) {
    case 'ONLINE':
      return { label: '在线', tone: 'success' }
    case 'SLEEP':
      return { label: '休眠', tone: 'warning' }
    default:
      return { label: '离线', tone: 'muted' }
  }
}

/** 闸杆状态徽标。抬杆 = 放行，落杆 = 占位 */
export function gateBadge(gateStatus: GateStatus): Badge {
  switch (gateStatus) {
    case 'OPENED':
      return { label: '已抬杆', tone: 'success' }
    case 'CLOSED':
      return { label: '已落杆', tone: 'info' }
    case 'OPENING':
      return { label: '抬杆中', tone: 'info' }
    case 'CLOSING':
      return { label: '落杆中', tone: 'info' }
    case 'STOPPED':
      return { label: '中途停止', tone: 'warning' }
    case 'FAULT':
      return { label: '故障', tone: 'danger' }
    default:
      return { label: '状态未知', tone: 'muted' }
  }
}

export interface Battery {
  /** 86% / — */
  text: string
  /** 0–4 格，未知为 -1 */
  bars: number
  /** ≤ 20% 显示红色并提示充电 */
  low: boolean
}

export const LOW_BATTERY_PCT = 20

export function battery(pct: number | null | undefined): Battery {
  if (pct === null || pct === undefined || !Number.isFinite(pct)) return { text: '—', bars: -1, low: false }
  const value = Math.min(100, Math.max(0, Math.round(pct)))
  return { text: `${value}%`, bars: value === 0 ? 0 : Math.ceil(value / 25), low: value <= LOW_BATTERY_PCT }
}

/** 卡片标题：没起名时用 SN 后 4 位 */
export function deviceTitle(card: Pick<DeviceCard, 'name' | 'sn'>): string {
  const name = card.name?.trim()
  return name ? name : `车位 ${card.sn.slice(-4)}`
}

const HOUR = 3_600_000
const DAY = 24 * HOUR
const OFFSET = 8 * HOUR

/** 东八区的年月日时分 */
function parts(ms: number) {
  const d = new Date(ms + OFFSET)
  return {
    y: d.getUTCFullYear(),
    mo: d.getUTCMonth() + 1,
    d: d.getUTCDate(),
    h: d.getUTCHours(),
    mi: d.getUTCMinutes(),
    dayIndex: Math.floor((ms + OFFSET) / DAY),
  }
}

const pad = (n: number) => String(n).padStart(2, '0')

/**
 * 列表里的时间：今天 10:05 / 昨天 10:05 / 11-27 10:05（今年）/ 2025-11-27（往年）。按东八区显示，与手机时区无关。
 * 解析失败时原样返回。
 */
export function formatTime(iso: string, now: number = Date.now()): string {
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return iso
  const t = parts(ms)
  const n = parts(now)
  const hm = `${pad(t.h)}:${pad(t.mi)}`
  if (t.dayIndex === n.dayIndex) return `今天 ${hm}`
  if (t.dayIndex === n.dayIndex - 1) return `昨天 ${hm}`
  if (t.y === n.y) return `${pad(t.mo)}-${pad(t.d)} ${hm}`
  return `${t.y}-${pad(t.mo)}-${pad(t.d)}`
}

/** 有效期：永久有效 / 有效期至 12-31 18:00 / 已到期 */
export function validity(validTo: string | null | undefined, now: number = Date.now()): string {
  if (!validTo) return '永久有效'
  const ms = Date.parse(validTo)
  if (Number.isNaN(ms)) return validTo
  if (ms <= now) return '已到期'
  const t = parts(ms)
  const n = parts(now)
  const date = t.y === n.y ? `${pad(t.mo)}-${pad(t.d)}` : `${t.y}-${pad(t.mo)}-${pad(t.d)}`
  return `有效期至 ${date} ${pad(t.h)}:${pad(t.mi)}`
}

/**
 * 控制指令的幂等键（契约：每次点击一个 UUID，重试复用）。小程序没有 crypto.randomUUID，按 v4 格式随机生成。
 */
export function idempotencyKey(random: () => number = Math.random): string {
  const hex = (n: number) =>
    Array.from({ length: n }, () => Math.floor(random() * 16).toString(16)).join('')
  const variant = '89ab'[Math.floor(random() * 4)]
  return `${hex(8)}-${hex(4)}-4${hex(3)}-${variant}${hex(3)}-${hex(12)}`
}
