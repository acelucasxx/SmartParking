import { ErrorCode } from '../api/errors'

/**
 * 设备设置（MP-09，PRD 5.7①、6.1 第 10 页）的纯逻辑：名称与车位号的修改请求、解绑与退出授权的说明和失败处理。
 * 绑定结果页（MP-05）设置名称与车位号也用这里的 devicePatch。
 */

export const TEXT_MAX = 32

/** 按字符计数，与服务端 codePointCount 一致（emoji 算一个） */
export function charCount(s: string): number {
  return Array.from(s).length
}

export interface Names {
  name?: string | null
  spaceNo?: string | null
}

/**
 * 名称、车位号的修改请求（PATCH /devices/{id}，只改出现的字段）：
 * - 名称去首尾空白后 1–32 个字符，不能清空（服务端 10001）；原来没有名称时留空表示不设置
 * - 车位号至多 32 个字符，空表示清空
 * - 没有变化的字段不发；都没变化时 patch 为 null
 */
export function devicePatch(
  original: Names,
  name: string,
  spaceNo: string,
): { ok: true; patch: { name?: string; spaceNo?: string } | null } | { ok: false; message: string } {
  const n = name.trim()
  const s = spaceNo.trim()
  // 服务端存的已去首尾空白
  const oldName = original.name ?? ''
  const oldSpace = original.spaceNo ?? ''
  if (charCount(n) > TEXT_MAX) return { ok: false, message: `名称最多 ${TEXT_MAX} 个字` }
  if (charCount(s) > TEXT_MAX) return { ok: false, message: `车位号最多 ${TEXT_MAX} 个字` }
  if (!n && oldName) return { ok: false, message: '名称不能为空' }
  const patch: { name?: string; spaceNo?: string } = {}
  // 名称为空时：原来有名称已在上面拦下，原来没有名称则与原值相同，不发
  if (n !== oldName) patch.name = n
  if (s !== oldSpace) patch.spaceNo = s
  return { ok: true, patch: Object.keys(patch).length ? patch : null }
}

/** 主人解绑的二次确认（PRD 5.7①），倒计时防误点 */
export const UNBIND_CONFIRM = {
  title: '解绑设备',
  content: '解绑后设备回到待激活，你和全部家人将失去权限，进行中的邀请与转让作废。重新绑定需要 SN 和包装内的激活码。',
  confirmText: '解绑',
  countdown: 3,
}

/** 被授权人退出授权 */
export const QUIT_CONFIRM = {
  title: '退出授权',
  content: '退出后你将无法再使用这个车位，主人会收到通知；如需再次使用，请让主人重新邀请。',
  confirmText: '退出',
  countdown: 0,
}

/**
 * 解绑 / 退出失败：
 * - gone：已不是主人或授权已结束（并发转让、强制解绑、已到期等），提示后回首页刷新
 * - show：其余就地提示，可再试
 */
export function leaveFailure(code: number): 'gone' | 'show' {
  switch (code) {
    case ErrorCode.NOT_FOUND:
    case ErrorCode.DEVICE_NOT_FOUND:
    case ErrorCode.NOT_OWNER:
    case ErrorCode.ACCESS_EXPIRED:
      return 'gone'
    default:
      return 'show'
  }
}
