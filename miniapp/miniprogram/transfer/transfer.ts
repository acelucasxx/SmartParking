import { ErrorCode } from '../api/errors'
import type { DateTime } from '../api/types'

/**
 * 设备转让（MP-08，PRD 5.6、6.1 第 9 页）的纯逻辑：发起前的风险提示、发起失败、转让卡片的失效态与接收失败。
 * 规则说明见 docs/biz/transfer-unbind.md。token 格式同邀请（invite/accept.ts 的 tokenValid）。
 */

export const ACCEPT_PAGE = '/pages/transfer/accept'

/** 契约 PendingTransfer */
export interface PendingTransfer {
  id: number
  expireAt: DateTime
  createdAt: DateTime
}

/** 契约 TransferPreview */
export interface TransferPreview {
  deviceName: string
  sn: string
  fromNickname: string
  expireAt: DateTime
  state: TransferState
}

export type TransferState = 'VALID' | 'EXPIRED' | 'ACCEPTED' | 'CANCELLED' | 'OWN_TRANSFER'

/** 发起前必须阅读的风险提示（PRD 5.6①⑤⑥） */
export const RISKS: string[] = [
  '对方接收后，你将失去这台设备的全部权限，无法再控制或查看。',
  '现有家人（被授权人）会在对方接收时全部清空，并收到通知。',
  '对方不需要到现场，也不会重置激活码。',
  '卡片 24 小时内有效、只能被一人接收；对方接收前你可以随时取消。',
]

/** 接收前提示接收方 */
export const ACCEPT_NOTES: string[] = [
  '接收后你将成为这台设备的主人，可以控制、邀请家人、转让或解绑。',
  '原主人和他邀请的家人将失去权限；你只能看到接收之后的操作记录。',
]

export interface Invalid {
  title: string
  desc: string
}

/** 转让卡片的失效态（PRD 6.1 第 9 页：卡片已过期、已取消），另有已被接收、自己发起的 */
export const INVALID: Record<Exclude<TransferState, 'VALID'>, Invalid> = {
  EXPIRED: { title: '转让卡片已过期', desc: '卡片 24 小时内有效，请让原主人重新发起转让。' },
  ACCEPTED: { title: '设备已被接收', desc: '这张转让卡片已被使用，每张卡片只能被一人接收。' },
  CANCELLED: { title: '转让已取消', desc: '原主人已取消转让，或设备已解绑、已更换主人。' },
  OWN_TRANSFER: { title: '这是你发起的转让', desc: '请把卡片发给接收人，由对方打开并确认接收。' },
}

export const NOT_FOUND: Invalid = { title: '转让卡片不存在', desc: '链接可能不完整，请让原主人重新发送卡片。' }

/**
 * 接收失败（服务端再次校验，PRD 5.6⑥）：
 * - state：切到对应失效态
 * - invalid：设备已停用、卡片不存在
 * - retry：网络等，可再试
 */
export type AcceptFailure =
  | { kind: 'state'; state: Exclude<TransferState, 'VALID'> }
  | { kind: 'invalid'; invalid: Invalid }
  | { kind: 'retry'; message: string }

export function acceptFailure(code: number, message: string): AcceptFailure {
  switch (code) {
    case ErrorCode.TRANSFER_EXPIRED:
      return { kind: 'state', state: 'EXPIRED' }
    case ErrorCode.TRANSFER_USED:
      return { kind: 'state', state: 'ACCEPTED' }
    case ErrorCode.TRANSFER_CANCELLED:
      return { kind: 'state', state: 'CANCELLED' }
    case ErrorCode.CANNOT_ACCEPT_OWN:
      return { kind: 'state', state: 'OWN_TRANSFER' }
    case ErrorCode.DEVICE_DISABLED:
      return { kind: 'invalid', invalid: { title: '设备已停用', desc: '该设备已被停用，暂时无法接收，如有疑问请联系客服。' } }
    case ErrorCode.NOT_FOUND:
      return { kind: 'invalid', invalid: NOT_FOUND }
    default:
      return { kind: 'retry', message }
  }
}

/**
 * 发起转让失败：
 * - reload：已有进行中的转让（30023，如另一台手机刚发起），重新加载显示它
 * - leave：已不是主人、设备不存在
 * - show：就地提示（设备已停用等）
 */
export function startFailure(code: number): 'reload' | 'leave' | 'show' {
  switch (code) {
    case ErrorCode.TRANSFER_PENDING_EXISTS:
      return 'reload'
    case ErrorCode.NOT_OWNER:
    case ErrorCode.NOT_FOUND:
    case ErrorCode.DEVICE_NOT_FOUND:
      return 'leave'
    default:
      return 'show'
  }
}

/** 发起确认弹窗的倒计时，防止误点（转让不可撤回） */
export const CONFIRM_COUNTDOWN = 3
