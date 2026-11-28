import { ErrorCode } from '../api/errors'
import type { DateTime } from '../api/types'
import { type Period, periodText, shortDateTime } from '../members/period'

/**
 * 接受邀请（MP-07，PRD 5.5④⑧、6.1 第 8 页）的纯逻辑：token 检查、预览状态 → 页面、接受失败的处理、接受后的去向。
 * 规则说明见 docs/biz/invites.md“预览”“接受”。
 */

export const ACCEPT_PAGE = '/pages/invite/accept'

/** 契约 InvitePreview */
export interface InvitePreview {
  deviceName: string
  ownerNickname: string
  period: Period
  remark?: string | null
  expireAt: DateTime
  state: InviteState
}

export type InviteState = 'VALID' | 'EXPIRED' | 'ACCEPTED' | 'CANCELLED' | 'ALREADY_MEMBER' | 'OWN_INVITE'

/** 分享卡片里的一次性 token：URL 安全 Base64，22–64 位（契约 parameters/Token） */
export function tokenValid(token: string | null | undefined): token is string {
  return !!token && /^[A-Za-z0-9_-]{22,64}$/.test(token)
}

/**
 * 页面上的失效态（PRD 6.1 第 8 页：已过期、已被他人接受、已撤销、自己已是成员），另有“自己发出的邀请”。
 * action：home 回首页看我的车位 / none 只有说明
 */
export interface Invalid {
  title: string
  desc: string
  action: 'home' | 'none'
}

export const INVALID: Record<Exclude<InviteState, 'VALID'>, Invalid> = {
  EXPIRED: { title: '邀请已过期', desc: '邀请卡片 24 小时内有效，请让主人重新发一张。', action: 'none' },
  ACCEPTED: { title: '邀请已被他人接受', desc: '每张邀请卡片只能被一个人接受，请让主人重新邀请你。', action: 'none' },
  CANCELLED: { title: '邀请已撤销', desc: '主人已撤回这张邀请，或设备已更换主人。', action: 'none' },
  ALREADY_MEMBER: { title: '你已是该设备的成员', desc: '无需重复接受，可以在“我的车位”里直接使用。', action: 'home' },
  OWN_INVITE: { title: '这是你发出的邀请', desc: '请把卡片发给家人，由对方打开并接受。', action: 'home' },
}

/** 未找到邀请（token 不存在，10004）或链接不完整 */
export const NOT_FOUND: Invalid = { title: '邀请不存在', desc: '链接可能不完整，请让主人重新发送邀请卡片。', action: 'none' }

/**
 * 接受失败后怎么办（服务端再次校验，PRD 5.5⑧）：
 * - state：切到对应失效态（预览之后状态变了）
 * - invalid：其他无法接受的情况，给出说明
 * - retry：网络等，可再试
 */
export type AcceptFailure =
  | { kind: 'state'; state: Exclude<InviteState, 'VALID'> }
  | { kind: 'invalid'; invalid: Invalid }
  | { kind: 'retry'; message: string }

export function acceptFailure(code: number, message: string): AcceptFailure {
  switch (code) {
    case ErrorCode.INVITE_EXPIRED:
      return { kind: 'state', state: 'EXPIRED' }
    case ErrorCode.INVITE_USED:
      return { kind: 'state', state: 'ACCEPTED' }
    case ErrorCode.INVITE_CANCELLED:
      return { kind: 'state', state: 'CANCELLED' }
    case ErrorCode.ALREADY_MEMBER:
      return { kind: 'state', state: 'ALREADY_MEMBER' }
    case ErrorCode.CANNOT_ACCEPT_OWN:
      return { kind: 'state', state: 'OWN_INVITE' }
    case ErrorCode.MEMBER_LIMIT_REACHED:
      return { kind: 'invalid', invalid: { title: '成员名额已满', desc: '主人的家人名额已用完，请联系主人调整后重新邀请。', action: 'none' } }
    case ErrorCode.DEVICE_DISABLED:
      return { kind: 'invalid', invalid: { title: '设备已停用', desc: '该设备已被停用，暂时无法加入，如有疑问请联系客服。', action: 'none' } }
    case ErrorCode.NOT_FOUND:
      return { kind: 'invalid', invalid: NOT_FOUND }
    default:
      return { kind: 'retry', message }
  }
}

/** 预览里的有效期说明 */
export function previewPeriod(preview: Pick<InvitePreview, 'period'>, now: number): string {
  return periodText(preview.period, now)
}

/**
 * 接受成功后：自定义有效期尚未开始时，车位要到开始时刻才出现在列表里（docs/biz/invites.md），
 * 这时不进控制页，提示开始时间后回首页。
 */
export function afterAccept(period: Period, now: number): { started: boolean; text: string } {
  if (period.type === 'CUSTOM' && Date.parse(period.validFrom) > now) {
    return { started: false, text: `将于 ${shortDateTime(period.validFrom, now)} 起生效，届时会出现在“我的车位”` }
  }
  return { started: true, text: '现在就可以使用了' }
}
