/**
 * 会话超时（PRD 6.2“会话超时”）。服务端空闲 30 分钟、自登录起 12 小时作废会话（docs/biz/admin-auth.md）；
 * 前端按同样的口径在页面上主动提示，不必等下一次请求返回 10002 才发现。
 *
 * - 空闲：以最近一次成功的接口请求为准（服务端每次请求顺延空闲超时）
 * - 绝对：自登录起 12 小时，顺延也不超过它
 */
export const IDLE_MS = 30 * 60_000
export const MAX_MS = 12 * 3_600_000
/** 到期前多久提醒“即将超时” */
export const WARN_BEFORE_MS = 2 * 60_000

export interface SessionTimes {
  loginAt: number
  lastActivityAt: number
}

/** 会话到期时刻：空闲到期与绝对到期取早者 */
export function expiresAt(t: SessionTimes, idleMs = IDLE_MS, maxMs = MAX_MS): number {
  return Math.min(t.lastActivityAt + idleMs, t.loginAt + maxMs)
}

export type SessionPhase = 'active' | 'expiring' | 'expired'

export function sessionPhase(
  t: SessionTimes,
  now: number,
  idleMs = IDLE_MS,
  maxMs = MAX_MS,
): SessionPhase {
  const end = expiresAt(t, idleMs, maxMs)
  if (now >= end) return 'expired'
  if (end - now <= WARN_BEFORE_MS) return 'expiring'
  return 'active'
}
