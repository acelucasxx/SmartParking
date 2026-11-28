/**
 * 接口契约（docs/api/openapi-app.yaml、common.yaml）的 TypeScript 类型。
 * 这里只放多个页面共用的结构；某个页面独有的请求体 / 响应在对应页面任务（MP-02 起）里补充。
 */

/** ISO 8601，带时区偏移，统一东八区，如 2026-12-31T18:00:00+08:00 */
export type DateTime = string

export type OnlineState = 'ONLINE' | 'SLEEP' | 'OFFLINE'
export type GateStatus = 'OPENED' | 'CLOSED' | 'OPENING' | 'CLOSING' | 'STOPPED' | 'FAULT' | 'UNKNOWN'
export type GateAction = 'OPEN' | 'CLOSE'
export type Channel = 'REMOTE' | 'BLE'
export type MemberRole = 'OWNER' | 'MEMBER'
export type CommandStatus = 'PENDING' | 'SENT' | 'EXECUTING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT'
export type CommandResultCode =
  | 'ALREADY_IN_TARGET'
  | 'MOTOR_FAULT'
  | 'OBSTRUCTED'
  | 'INFRARED'
  | 'INTERRUPTED'
  | 'TIMEOUT'
  | 'OFFLINE'
export type TriggerSource =
  | 'APP'
  | 'REMOTE_CONTROL'
  | 'GROUND_SENSOR'
  | 'EXTERNAL_SWITCH'
  | 'AUTO_CLOSE'
  | 'OBSTRUCTED'
  | 'INFRARED'
  | 'POWER_ON'
  | 'UNKNOWN'
export type MessageType = 'GRANT' | 'REVOKE' | 'EXPIRE' | 'TRANSFER' | 'UNBIND' | 'FAULT' | 'SYSTEM'

/** 分页结果 */
export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

// ------------------------------------------------------------ 登录

export interface UserProfile {
  id: number
  nickname: string
  /** 未授权手机号时前端引导授权，绑定设备前必须完成 */
  phoneBound: boolean
  phoneMasked?: string | null
}

export interface LoginResult {
  token: string
  expiresAt: DateTime
  user: UserProfile
}

// ------------------------------------------------------------ 设备

/** 车位首页卡片 */
export interface DeviceCard {
  id: number
  sn: string
  name?: string | null
  spaceNo?: string | null
  role: MemberRole
  /** 被授权人的有效期止；主人或永久授权为 null */
  validTo?: DateTime | null
  /** false 时卡片显示“设备已停用，请联系客服” */
  enabled: boolean
  onlineState: OnlineState
  gateStatus: GateStatus
  /** 车位有车；未知为 null */
  occupied?: boolean | null
  /** 未上报电量时为 null，显示“—” */
  batteryPct?: number | null
  /** 有未终结的指令时返回，控制页据此继续轮询 */
  pendingCommandId?: number | null
}

/** 控制页 5 秒刷新的轻量状态 */
export interface DeviceStatus {
  id: number
  enabled: boolean
  onlineState: OnlineState
  gateStatus: GateStatus
  occupied?: boolean | null
  batteryPct?: number | null
  signalLevel?: number | null
  statusRefreshedAt?: DateTime | null
  lastSeenAt?: DateTime | null
  pendingCommandId?: number | null
}

export interface DeviceDetail extends DeviceCard {
  model: string
  fwVersion?: string | null
  signalLevel?: number | null
  statusRefreshedAt?: DateTime | null
  lastSeenAt?: DateTime | null
  ownerNickname?: string
  /** 蓝牙入口是否上线；false 时离线状态只显示原因 */
  bleAvailable: boolean
  lastOperation?: OperationRecord | null
}

// ------------------------------------------------------------ 控制

export interface Command {
  id: number
  deviceId: number
  action: GateAction
  channel: Channel
  status: CommandStatus
  /** 执行中的闸杆状态，OPENING / CLOSING 时播放动画 */
  gateStatus?: GateStatus
  resultCode?: CommandResultCode | null
  /** 失败或超时时可直接展示的说明 */
  resultMessage?: string | null
  issuedAt: DateTime
  finishedAt?: DateTime | null
  durationMs?: number | null
}

export interface OperationRecord {
  /** c- 指令 / e- 设备事件，只用于列表去重 */
  id: string
  at: DateTime
  /** true 表示由定时查询发现，时间为发现时间，显示“约” */
  approx: boolean
  source: TriggerSource
  action: GateAction
  operatorName?: string | null
  operatorRole?: MemberRole | null
  channel?: Channel | null
  result?: 'SUCCESS' | 'FAILED' | 'TIMEOUT' | null
  resultCode?: CommandResultCode | null
  durationMs?: number | null
}

// ------------------------------------------------------------ 消息

export interface Message {
  id: number
  type: MessageType
  deviceId?: number | null
  title: string
  content: string
  read: boolean
  createdAt: DateTime
}
