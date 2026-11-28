import { luhn, normalizeMac } from './rules.ts'

/**
 * 设备运维操作（ADM-06，PRD 5.7、5.8、6.2）的前端规则：哪些操作可用、原因检查、换件新值检查、错误对应、自检轮询。
 * 规则见 docs/biz/admin-ops.md。不依赖 Vue，可在 Node 里直接单测。
 */

export type OpKey = 'forceUnbind' | 'resetCode' | 'disable' | 'enable' | 'replacePart' | 'selfCheck'

export interface OpDef {
  key: OpKey
  label: string
  /** 需要的权限点（V10 角色权限） */
  permission: string
  /** 高危操作：必须填原因 */
  needsReason: boolean
  danger: boolean
  /** 弹窗里的后果说明 */
  warning: string
}

export const OPS: OpDef[] = [
  {
    key: 'forceUnbind',
    label: '强制解绑',
    permission: 'device:force-unbind',
    needsReason: true,
    danger: true,
    warning:
      '设备回到待激活：清空主人、名称与车位号，全部被授权人失效，待接受的邀请和转让作废；原主人与被授权人会收到消息。用于抢绑申诉、二手转卖未转让等场景。',
  },
  {
    key: 'resetCode',
    label: '重置激活码',
    permission: 'device:reset-code',
    needsReason: true,
    danger: true,
    warning:
      '旧激活码立即失效，新激活码只显示这一次；绑定关系不变，同时清除该设备的绑定错误计数与锁定。',
  },
  {
    key: 'disable',
    label: '禁用设备',
    permission: 'device:disable',
    needsReason: true,
    danger: true,
    warning:
      '禁用后控制、邀请、转让都会被拒绝，小程序显示“设备已停用”；绑定与授权关系保留，启用后恢复。',
  },
  {
    key: 'enable',
    label: '启用设备',
    permission: 'device:disable',
    needsReason: true,
    danger: false,
    warning: '启用后恢复控制、邀请与转让。',
  },
  {
    key: 'replacePart',
    label: '换件',
    permission: 'device:replace-part',
    needsReason: true,
    danger: true,
    warning:
      '只改 IMEI（换 4G 模块）或蓝牙 MAC（换主板），SN 与绑定、授权关系不变。换 4G 模块后旧 IMEI 立即不再登记，旧模块的上行会被丢弃。',
  },
  {
    key: 'selfCheck',
    label: '设备自检',
    permission: 'device:self-check',
    needsReason: false,
    danger: false,
    warning: '只下发查询状态或查询版本，不动闸杆。设备离线、休眠或有未完成的指令时无法自检。',
  },
]

export function op(key: OpKey): OpDef {
  return OPS.find((o) => o.key === key)!
}

export interface DeviceState {
  bindStatus: 'PENDING' | 'BOUND'
  enabled: boolean
}

/**
 * 当前账号能看到的操作，以及每个操作此刻是否可用（不可用时给出原因，按钮置灰并提示）。
 * 禁用 / 启用按当前状态只显示一个。
 */
export function availableOps(
  device: DeviceState,
  can: (permission: string) => boolean,
): Array<{ op: OpDef; disabledReason: string | null }> {
  return OPS.filter((o) => can(o.permission))
    .filter((o) =>
      o.key === 'disable' ? device.enabled : o.key === 'enable' ? !device.enabled : true,
    )
    .map((o) => ({
      op: o,
      disabledReason:
        o.key === 'forceUnbind' && device.bindStatus !== 'BOUND' ? '设备未绑定' : null,
    }))
}

export const REASON_MIN = 2
export const REASON_MAX = 255

/** 原因：去首尾空白后 2–255 个字符（按字符计，与服务端一致） */
export function reasonProblem(reason: string): string | null {
  const n = Array.from(reason.trim()).length
  if (n < REASON_MIN) return `请填写原因（至少 ${REASON_MIN} 个字）`
  if (n > REASON_MAX) return `原因最多 ${REASON_MAX} 个字`
  return null
}

export type Part = 'DTU' | 'BOARD'

/**
 * 换件的新值：DTU 为 15 位 IMEI（Luhn 校验），BOARD 为蓝牙 MAC（规整为大写冒号分隔）；不能与当前值相同。
 */
export function partValue(
  part: Part,
  raw: string,
  current: { imei: string; bleMac?: string | null },
): { ok: true; value: string } | { ok: false; message: string } {
  if (part === 'DTU') {
    const imei = raw.replace(/\s+/g, '')
    if (!/^\d{15}$/.test(imei)) return { ok: false, message: '新 IMEI 应为 15 位数字' }
    if (!luhn(imei)) return { ok: false, message: '新 IMEI 校验位不正确，请核对' }
    if (imei === current.imei) return { ok: false, message: '新 IMEI 与当前相同' }
    return { ok: true, value: imei }
  }
  const mac = normalizeMac(raw)
  if (!mac) return { ok: false, message: '请填写正确的蓝牙 MAC，如 AA:BB:CC:DD:EE:FF' }
  if (mac === current.bleMac) return { ok: false, message: '新 MAC 与当前相同' }
  return { ok: true, value: mac }
}

/** 服务端错误码对应弹窗里的哪个输入框（其余在弹窗底部提示） */
export function errorField(code: number): 'reason' | 'value' | null {
  switch (code) {
    case 40020:
      return 'reason'
    case 40011:
    case 40012:
    case 40013:
      return 'value'
    default:
      return null
  }
}

/** 自检结果轮询：每秒查一次，指令终结或等满上限（服务端默认 15 秒超时，再留余量）为止 */
export const SELF_CHECK_POLL_MS = 1000
export const SELF_CHECK_MAX_MS = 25_000

export function commandFinished(status: string): boolean {
  return status === 'SUCCESS' || status === 'FAILED' || status === 'TIMEOUT'
}

/**
 * 自检下发失败的提示。服务端的 20003 / 20004 文案是写给小程序用户的（“可在现场使用蓝牙”），后台换成运维视角的说明。
 */
export function selfCheckError(code: number, message: string): string {
  switch (code) {
    case 20003:
      return '设备离线，无法自检（最近 24 小时没有上行），请检查电池与安装位置'
    case 20004:
      return '设备休眠中，暂时无法自检，可稍后再试'
    case 20005:
      return '设备正在执行其他指令，请稍后再试'
    default:
      return message
  }
}
