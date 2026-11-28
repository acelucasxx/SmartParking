import { ACTION_LABEL, type PrimaryButton, operationText } from './control'
import type { ControlState } from './controller'
import { type Badge, deviceTitle, formatTime, gateBadge, occupancy, onlineBadge, signal, validity } from '../utils/format'

/** 控制页渲染用的数据：页面只 setData，不做判断 */
export interface DeviceViewModel {
  title: string
  subtitle: string
  roleLabel: string
  online: Badge
  gate: Badge
  /** 闸杆图示：opened / closed / opening / closing / unknown */
  gatePose: string
  occupancy: string
  batteryPct: number | null
  signal: string
  refreshedAt: string
  lastOperation: string
  button: PrimaryButton
  /** 两个按钮的文案与是否为建议动作 */
  openLabel: string
  closeLabel: string
  openPrimary: boolean
  closePrimary: boolean
  /** 结果条：执行阶段或一次性提示 */
  result: { text: string; tone: string } | null
  stale: boolean
}

export function viewModel(state: ControlState, now: number): DeviceViewModel | null {
  const d = state.device
  if (!d || !state.button) return null
  const animation = state.phase && !state.phase.done ? state.phase.animation : null
  const gate = animation === 'opening' ? 'OPENING' : animation === 'closing' ? 'CLOSING' : d.gateStatus
  const button = state.button
  const primary = button.kind === 'control' ? button.primary : null
  const subtitle = [d.spaceNo ? `车位号 ${d.spaceNo}` : '', d.role === 'MEMBER' ? validity(d.validTo, now) : ''].filter(Boolean).join(' · ')
  let result: DeviceViewModel['result'] = null
  if (state.notice) result = { text: state.notice, tone: 'warning' }
  else if (state.phase) result = { text: state.phase.text, tone: state.phase.tone }
  return {
    title: deviceTitle(d),
    subtitle,
    roleLabel: d.role === 'OWNER' ? '我的' : '家人共享',
    online: onlineBadge(d.enabled, d.onlineState, d.gateStatus),
    gate: gateBadge(gate),
    gatePose: pose(gate),
    occupancy: occupancy(d.occupied),
    batteryPct: d.batteryPct ?? null,
    signal: signal(d.signalLevel).text,
    refreshedAt: d.statusRefreshedAt ? `状态更新于 ${formatTime(d.statusRefreshedAt, now)}` : '暂未收到设备状态',
    lastOperation: d.lastOperation ? operationText(d.lastOperation, now) : '暂无操作',
    button,
    openLabel: ACTION_LABEL.OPEN,
    closeLabel: ACTION_LABEL.CLOSE,
    openPrimary: primary === 'OPEN',
    closePrimary: primary === 'CLOSE',
    result,
    stale: state.stale,
  }
}

function pose(gate: string): string {
  switch (gate) {
    case 'OPENED':
      return 'opened'
    case 'CLOSED':
      return 'closed'
    case 'OPENING':
      return 'opening'
    case 'CLOSING':
      return 'closing'
    default:
      return 'unknown'
  }
}
