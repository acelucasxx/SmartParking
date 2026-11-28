import { ACTION_LABEL, type PrimaryButton, primaryButton } from '../device/control'
import { type Badge, battery, deviceTitle, gateBadge, onlineBadge, validity } from '../utils/format'
import { type CardState, view } from './controller'

/** 首页卡片的渲染数据：页面只 setData */
export interface CardViewModel {
  id: number
  title: string
  spaceNo: string
  /** 我的 / 家人共享（PRD 6.2 角色标识） */
  roleLabel: string
  roleTone: string
  /** 被授权人的有效期；主人为空 */
  validity: string
  online: Badge
  gate: Badge
  batteryPct: number | null
  battery: string
  button: PrimaryButton
  openLabel: string
  closeLabel: string
  openPrimary: boolean
  closePrimary: boolean
  /** 卡片底部的结果条 */
  result: { text: string; tone: string } | null
}

export function cardViewModel(cs: CardState, now: number): CardViewModel {
  const c = cs.card
  const button = primaryButton(view(c), cs.phase)
  const primary = button.kind === 'control' ? button.primary : null
  const animation = cs.phase && !cs.phase.done ? cs.phase.animation : null
  const gate = animation === 'opening' ? 'OPENING' : animation === 'closing' ? 'CLOSING' : c.gateStatus
  let result: CardViewModel['result'] = null
  if (cs.notice) result = { text: cs.notice, tone: 'warning' }
  else if (cs.phase) result = { text: cs.phase.text, tone: cs.phase.tone }
  return {
    id: c.id,
    title: deviceTitle(c),
    spaceNo: c.spaceNo ?? '',
    roleLabel: c.role === 'OWNER' ? '我的' : '家人共享',
    roleTone: c.role === 'OWNER' ? 'info' : 'success',
    validity: c.role === 'MEMBER' ? validity(c.validTo, now) : '',
    online: onlineBadge(c.enabled, c.onlineState, c.gateStatus),
    gate: gateBadge(gate),
    batteryPct: c.batteryPct ?? null,
    battery: battery(c.batteryPct).text,
    button,
    openLabel: ACTION_LABEL.OPEN,
    closeLabel: ACTION_LABEL.CLOSE,
    openPrimary: primary === 'OPEN',
    closePrimary: primary === 'CLOSE',
    result,
  }
}
