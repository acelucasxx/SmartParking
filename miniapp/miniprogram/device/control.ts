import { ErrorCode } from '../api/errors'
import type {
  Command,
  CommandResultCode,
  DeviceDetail,
  DeviceStatus,
  GateAction,
  GateStatus,
  OperationRecord,
  TriggerSource,
} from '../api/types'
import { formatTime } from '../utils/format'

/**
 * 设备控制页（MP-04，PRD 6.1 第 3 页、6.2）的纯逻辑：三态主按钮、指令阶段、轮询间隔、最近一次操作的文案。
 * 页面在 pages/device，轮询编排在 device/controller.ts。
 */

/** 控制页停留期间的状态刷新间隔 */
export const REFRESH_MS = 5000
/** 执行中轮询结果的间隔 */
export const POLL_MS = 1000
/**
 * 前端最多等多久的结果。服务端 15 秒判超时，加上每 15 秒一次的对账，最晚约 30 秒给出终态；
 * 再等不到就不再 1 秒轮询，按钮恢复，提示到操作记录里看结果，避免页面一直卡在“执行中”。
 */
export const MAX_WAIT_MS = 35_000

export const ACTION_LABEL: Record<GateAction, string> = { OPEN: '抬杆', CLOSE: '落杆' }

/** 页面关心的设备字段：详情加上 5 秒刷新的轻量状态 */
export type DeviceView = Pick<DeviceDetail, 'enabled' | 'onlineState' | 'gateStatus' | 'bleAvailable'> &
  Pick<DeviceStatus, 'pendingCommandId'>

/**
 * 三态主按钮（PRD 6.2）：
 * - control：在线，大按钮抬杆 / 落杆；primary 是建议的动作（已抬杆时建议落杆），中途停止或状态未知时为 null，两个按钮同等
 * - busy：有指令在执行，按钮置灰显示“执行中”
 * - ble：休眠或离线且蓝牙已上线，主按钮变为“蓝牙控制”，附一行原因
 * - unavailable：休眠或离线但蓝牙未上线，只显示原因
 * - support：故障或已停用，按钮禁用，显示说明与客服入口
 */
export type PrimaryButton =
  | { kind: 'control'; primary: GateAction | null }
  | { kind: 'busy'; label: string }
  | { kind: 'ble'; reason: string }
  | { kind: 'unavailable'; reason: string }
  | { kind: 'support'; reason: string }

export const OFFLINE_REASON = '设备离线，请检查电池电量和安装位置'
export const SLEEP_REASON = '设备休眠中，暂时无法远程控制'

export function primaryButton(device: DeviceView, phase: CommandPhase | null): PrimaryButton {
  // 停用、故障优先：远程与蓝牙都不可用
  if (!device.enabled) return { kind: 'support', reason: '设备已停用，请联系客服' }
  if (device.gateStatus === 'FAULT') return { kind: 'support', reason: '设备故障，请联系客服' }
  // 自己的指令在执行，或别人（同设备的主人 / 家人）的指令还没结束
  if ((phase && !phase.done) || device.pendingCommandId) return { kind: 'busy', label: '执行中…' }
  if (device.onlineState !== 'ONLINE') {
    const reason = device.onlineState === 'SLEEP' ? SLEEP_REASON : OFFLINE_REASON
    return device.bleAvailable ? { kind: 'ble', reason: `${reason}，可在现场使用蓝牙` } : { kind: 'unavailable', reason }
  }
  return { kind: 'control', primary: suggestedAction(device.gateStatus) }
}

function suggestedAction(gate: GateStatus): GateAction | null {
  if (gate === 'OPENED') return 'CLOSE'
  if (gate === 'CLOSED') return 'OPEN'
  return null
}

// ------------------------------------------------------------ 指令阶段

/**
 * 指令在页面上的阶段。只有到位才算成功（PRD 6.2）：
 * - sending：已提交，等设备响应
 * - moving：设备回报开闸中 / 关闸中，播放闸杆动画
 * - success / failed / timeout：终态
 * - unconfirmed：前端等满 MAX_WAIT_MS 仍无终态，不再轮询
 */
export type PhaseKind = 'sending' | 'moving' | 'success' | 'failed' | 'timeout' | 'unconfirmed'

export interface CommandPhase {
  kind: PhaseKind
  /** 终态（含 unconfirmed），停止 1 秒轮询 */
  done: boolean
  text: string
  tone: 'info' | 'success' | 'warning' | 'danger'
  /** 闸杆动画：抬杆中 / 落杆中 */
  animation: 'opening' | 'closing' | null
}

/** 失败原因的兜底文案，与服务端 resultMessage（docs/biz/remote-control.md）一致；服务端给了就用服务端的 */
export const RESULT_MESSAGES: Record<CommandResultCode, string> = {
  ALREADY_IN_TARGET: '',
  MOTOR_FAULT: '设备电机故障，请联系客服',
  OBSTRUCTED: '闸杆遇阻，请检查现场',
  INFRARED: '检测到障碍物，闸杆已停止',
  INTERRUPTED: '闸杆中途停止，请重试',
  TIMEOUT: '设备长时间未到位，请查看现场',
  OFFLINE: '设备连接中断，指令未送达',
}

/** @param elapsedMs 从点击（或进入页面发现未终结指令）起已等待的时间 */
export function commandPhase(cmd: Command, elapsedMs: number): CommandPhase {
  const label = ACTION_LABEL[cmd.action]
  switch (cmd.status) {
    case 'SUCCESS':
      return {
        kind: 'success',
        done: true,
        text: cmd.resultCode === 'ALREADY_IN_TARGET' ? `已是${cmd.action === 'OPEN' ? '抬杆' : '落杆'}状态` : `${label}成功，已到位`,
        tone: 'success',
        animation: null,
      }
    case 'FAILED':
      return { kind: 'failed', done: true, text: failureText(cmd, `${label}失败，请重试`), tone: 'danger', animation: null }
    case 'TIMEOUT':
      return { kind: 'timeout', done: true, text: failureText(cmd, RESULT_MESSAGES.TIMEOUT), tone: 'warning', animation: null }
  }
  if (elapsedMs >= MAX_WAIT_MS) {
    return { kind: 'unconfirmed', done: true, text: '暂未收到设备结果，请稍后在操作记录中查看', tone: 'warning', animation: null }
  }
  if (cmd.status === 'EXECUTING' && (cmd.gateStatus === 'OPENING' || cmd.gateStatus === 'CLOSING')) {
    return {
      kind: 'moving',
      done: false,
      text: cmd.gateStatus === 'OPENING' ? '抬杆中…' : '落杆中…',
      tone: 'info',
      animation: cmd.gateStatus === 'OPENING' ? 'opening' : 'closing',
    }
  }
  return { kind: 'sending', done: false, text: `正在${label}…`, tone: 'info', animation: null }
}

function failureText(cmd: Command, fallback: string): string {
  const server = cmd.resultMessage?.trim()
  if (server) return server
  return (cmd.resultCode && RESULT_MESSAGES[cmd.resultCode]) || fallback
}

/** 下一次轮询的间隔：执行中 1 秒，其余 5 秒 */
export function nextDelay(phase: CommandPhase | null, pendingCommandId: number | null | undefined): number {
  return (phase && !phase.done) || pendingCommandId ? POLL_MS : REFRESH_MS
}

// ------------------------------------------------------------ 点击被拒

/**
 * 点击抬杆 / 落杆被服务端拒绝时的处理：
 * - refresh：状态已变（离线、休眠、执行中、故障、停用），提示后立即刷新，按钮随之变化
 * - leave：设备或授权已不可用，提示后返回首页
 * - show：其余就地提示
 */
export function rejection(code: number): 'refresh' | 'leave' | 'show' {
  switch (code) {
    case ErrorCode.DEVICE_OFFLINE:
    case ErrorCode.DEVICE_SLEEPING:
    case ErrorCode.COMMAND_IN_PROGRESS:
    case ErrorCode.DEVICE_FAULT:
    case ErrorCode.DEVICE_DISABLED:
      return 'refresh'
    case ErrorCode.NOT_FOUND:
    case ErrorCode.DEVICE_NOT_FOUND:
    case ErrorCode.ACCESS_EXPIRED:
      return 'leave'
    default:
      return 'show'
  }
}

// ------------------------------------------------------------ 最近一次操作

const SOURCE_LABEL: Record<TriggerSource, string> = {
  APP: '',
  REMOTE_CONTROL: '遥控器',
  GROUND_SENSOR: '地感',
  EXTERNAL_SWITCH: '外部开关',
  AUTO_CLOSE: '延时自动',
  OBSTRUCTED: '遇阻',
  INFRARED: '红外防砸',
  POWER_ON: '上电找零',
  UNKNOWN: '',
}

/** 今天 10:05 · 张先生 远程抬杆 · 成功；非 App 触发：约 今天 10:05 · 遥控器抬杆 */
export function operationText(record: OperationRecord, now: number): string {
  const time = `${record.approx ? '约 ' : ''}${formatTime(record.at, now)}`
  const action = ACTION_LABEL[record.action]
  let who: string
  if (record.source === 'APP') {
    const channel = record.channel === 'BLE' ? '蓝牙' : '远程'
    who = `${record.operatorName ? `${record.operatorName} ` : ''}${channel}${action}`
  } else {
    who = `${SOURCE_LABEL[record.source]}${action}`
  }
  const result = resultLabel(record)
  return [time, who, result].filter(Boolean).join(' · ')
}

function resultLabel(record: OperationRecord): string {
  switch (record.result) {
    case 'SUCCESS':
      return '成功'
    case 'TIMEOUT':
      return '超时'
    case 'FAILED':
      return (record.resultCode && RESULT_MESSAGES[record.resultCode].split('，')[0]) || '失败'
    default:
      return ''
  }
}
