/**
 * 后台显示用的枚举文案与标签颜色（契约 common.yaml / openapi-admin.yaml 的枚举）。
 * 不依赖 Vue，可在 Node 里直接单测：测试会核对每个枚举值都有文案。
 */
export type TagType = 'success' | 'info' | 'warning' | 'danger' | 'primary'
export interface Label {
  text: string
  type: TagType
}

export const ONLINE_STATE = {
  ONLINE: { text: '在线', type: 'success' },
  SLEEP: { text: '休眠', type: 'warning' },
  OFFLINE: { text: '离线', type: 'info' },
} satisfies Record<string, Label>

export const GATE_STATUS = {
  OPENED: { text: '已抬杆', type: 'success' },
  CLOSED: { text: '已落杆', type: 'primary' },
  OPENING: { text: '抬杆中', type: 'primary' },
  CLOSING: { text: '落杆中', type: 'primary' },
  STOPPED: { text: '中途停止', type: 'warning' },
  FAULT: { text: '电机故障', type: 'danger' },
  UNKNOWN: { text: '未知', type: 'info' },
} satisfies Record<string, Label>

export const BIND_STATUS = {
  PENDING: { text: '待激活', type: 'info' },
  BOUND: { text: '已绑定', type: 'success' },
} satisfies Record<string, Label>

export const MEMBER_STATUS = {
  ACTIVE: { text: '生效中', type: 'success' },
  REVOKED: { text: '已撤销', type: 'info' },
  EXPIRED: { text: '已到期', type: 'warning' },
  QUIT: { text: '已退出', type: 'info' },
} satisfies Record<string, Label>

export const COMMAND_STATUS = {
  PENDING: { text: '待下发', type: 'info' },
  SENT: { text: '已下发', type: 'primary' },
  EXECUTING: { text: '执行中', type: 'primary' },
  SUCCESS: { text: '成功', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
  TIMEOUT: { text: '超时', type: 'warning' },
} satisfies Record<string, Label>

export const COMMAND_ACTION: Record<string, string> = {
  OPEN: '抬杆',
  CLOSE: '落杆',
  QUERY_STATUS: '查询状态',
  QUERY_VERSION: '查询版本',
}

export const RESULT_CODE: Record<string, string> = {
  ALREADY_IN_TARGET: '已在目标状态',
  MOTOR_FAULT: '电机故障',
  OBSTRUCTED: '遇阻',
  INFRARED: '红外防砸',
  INTERRUPTED: '中途停止',
  TIMEOUT: '15 秒未到位',
  OFFLINE: '链路中断未送达',
}

export const CHANNEL: Record<string, string> = { REMOTE: '远程', BLE: '蓝牙' }

export const OPERATOR_ROLE: Record<string, string> = {
  OWNER: '主人',
  MEMBER: '被授权人',
  ADMIN: '后台',
}

export const TRIGGER_SOURCE: Record<string, string> = {
  APP: '平台指令',
  REMOTE_CONTROL: '遥控器',
  GROUND_SENSOR: '地感 / 雷达',
  EXTERNAL_SWITCH: '外部开关',
  AUTO_CLOSE: '延时自动关',
  OBSTRUCTED: '遇阻',
  INFRARED: '红外防砸',
  POWER_ON: '上电找零',
  UNKNOWN: '未知',
}

export const ALARM_TYPE: Record<string, string> = {
  MOTOR_FAULT: '电机故障',
  OBSTRUCTED: '遇阻',
  INFRARED: '红外防砸',
  CMD_TIMEOUT: '连续指令超时',
  OFFLINE_24H: '离线超 24 小时',
}

export const ALARM_LEVEL = {
  CRITICAL: { text: '严重', type: 'danger' },
  WARNING: { text: '警告', type: 'warning' },
} satisfies Record<string, Label>

export const ALARM_STATUS = {
  OPEN: { text: '未处理', type: 'danger' },
  HANDLED: { text: '已处理', type: 'success' },
} satisfies Record<string, Label>

export const USER_STATUS = {
  ACTIVE: { text: '正常', type: 'success' },
  BANNED: { text: '已封禁', type: 'danger' },
} satisfies Record<string, Label>

export const WORK_MODE: Record<string, string> = { KEEP_ALIVE: '常在线', DEEP_SLEEP: '深度休眠' }

/** 取标签，未知值原样显示，不抛错（服务端新增枚举时页面不白屏） */
export function label(map: Record<string, Label>, value: string | null | undefined): Label {
  if (!value) return { text: '—', type: 'info' }
  return map[value] ?? { text: value, type: 'info' }
}

export function text(map: Record<string, string>, value: string | null | undefined): string {
  if (!value) return '—'
  return map[value] ?? value
}

/** 电量：86% / —（PRD 6.2 不做低电量提醒，后台也只显示数值） */
export function batteryText(pct: number | null | undefined): string {
  return pct === null || pct === undefined ? '—' : `${pct}%`
}

/** 信号 CSQ 0–31，99 为未知 */
export function signalText(level: number | null | undefined): string {
  if (level === null || level === undefined || level < 0 || level > 31) return '—'
  return `${level}（${level >= 20 ? '强' : level >= 10 ? '中' : level >= 1 ? '弱' : '无'}）`
}

export function occupiedText(occupied: boolean | null | undefined): string {
  return occupied === true ? '有车' : occupied === false ? '无车' : '—'
}

/** 毫秒耗时：1240 → 1.24 秒 */
export function durationText(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—'
  return ms < 1000 ? `${ms} 毫秒` : `${(ms / 1000).toFixed(2)} 秒`
}
