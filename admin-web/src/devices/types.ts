/** 契约 openapi-admin.yaml 中设备管理相关的结构 */

export interface Paged<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface AdminDevice {
  id: number
  sn: string
  imei: string
  bleMac?: string | null
  model: string
  batchNo: string
  bindStatus: 'PENDING' | 'BOUND'
  enabled: boolean
  onlineState: 'ONLINE' | 'SLEEP' | 'OFFLINE'
  gateStatus: string
  batteryPct?: number | null
  lastSeenAt?: string | null
  createdAt: string
}

export interface DeviceMember {
  userId: number
  nickname: string
  phoneMasked?: string | null
  remark?: string | null
  validFrom: string
  validTo?: string | null
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'QUIT'
}

export interface AdminDeviceDetail extends AdminDevice {
  iccid?: string | null
  fwVersion?: string | null
  workMode: 'KEEP_ALIVE' | 'DEEP_SLEEP'
  occupied?: boolean | null
  signalLevel?: number | null
  statusRefreshedAt?: string | null
  name?: string | null
  spaceNo?: string | null
  owner?: { userId: number; nickname: string; phoneMasked?: string | null; since: string } | null
  members: DeviceMember[]
}

export interface CommandAudit {
  id: number
  deviceId: number
  sn: string
  operator: { type: 'USER' | 'ADMIN'; id: number; name: string; phoneMasked?: string | null }
  role: 'OWNER' | 'MEMBER' | 'ADMIN'
  channel: 'REMOTE' | 'BLE'
  action: string
  status: string
  resultCode?: string | null
  fwVersion?: string | null
  downFrame?: string | null
  rawFrame?: string | null
  issuedAt: string
  finishedAt?: string | null
  durationMs?: number | null
}

export interface Alarm {
  id: number
  deviceId: number
  sn: string
  type: string
  level: 'CRITICAL' | 'WARNING'
  firstAt: string
  lastAt: string
  count: number
  status: 'OPEN' | 'HANDLED'
  handlerName?: string | null
  handledAt?: string | null
  note?: string | null
}

export interface DeviceEvent {
  id: number
  gateStatus: string
  statusCode?: string | null
  reasonCode?: string | null
  source: string
  occupied?: boolean | null
  approx: boolean
  rawFrame: string
  at: string
}
