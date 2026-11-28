import type { ApiClient } from '../api/client'
import type { UserProfile } from '../api/types'
import { AGREEMENT_VERSION } from '../content/agreements'

/**
 * 登录授权（MP-02）的纯逻辑：手机号授权结果判定、授权后回跳、协议同意记录。页面在 pages/login。
 *
 * 登录本身是静默的（启动时 wx.login，见 api/client.ts）；手机号只在需要时授权（扫码绑定前必须完成，PRD 5.2），
 * 不在启动时强制——平台规范不允许先要手机号才能浏览。
 */

export const LOGIN_PAGE = '/pages/login/login'
export const BANNED_PAGE = '/pages/banned/banned'
export const HOME_PAGE = '/pages/index/index'
/** tabBar 页面只能 switchTab 进入 */
export const TAB_PAGES = ['/pages/index/index', '/pages/message/message', '/pages/mine/mine']

export type PhoneResult =
  | { kind: 'code'; code: string }
  /** 用户点了“拒绝”：不算错误，提示后可再点 */
  | { kind: 'denied' }
  /** 其他失败（如额度用尽、基础库过低） */
  | { kind: 'failed'; message: string }

/** 判定 `<button open-type="getPhoneNumber">` 的回调 */
export function phoneResult(detail: { code?: string; errMsg?: string } | null | undefined): PhoneResult {
  const code = detail?.code?.trim()
  if (code) return { kind: 'code', code }
  const errMsg = detail?.errMsg ?? ''
  if (/deny|cancel/i.test(errMsg)) return { kind: 'denied' }
  return { kind: 'failed', message: '获取手机号失败，请重试' }
}

/** 用动态 code 换手机号（服务端加密存储），并更新本地缓存的资料 */
export async function bindPhone(api: ApiClient, code: string): Promise<UserProfile> {
  const user = await api.post<UserProfile>('/auth/phone', { code })
  api.updateUser(user)
  return user
}

/**
 * 授权后回跳的地址：只接受本小程序的页面路径（/pages/ 开头），其余一律回首页，避免被外部参数带到任意页面。
 * 参数是 encodeURIComponent 过的完整路径（可带查询串）。
 */
export function redirectTarget(raw: string | undefined | null): string {
  if (!raw) return HOME_PAGE
  let decoded: string
  try {
    decoded = decodeURIComponent(raw)
  } catch {
    return HOME_PAGE
  }
  // 路径部分只允许字母、数字、下划线、连字符和 /，所以 .. 之类也进不来
  if (!/^\/pages\/[A-Za-z0-9_\-/]+(\?[^#\s]*)?$/.test(decoded)) return HOME_PAGE
  if (decoded.startsWith(LOGIN_PAGE) || decoded.startsWith(BANNED_PAGE)) return HOME_PAGE
  return decoded
}

/** 回跳方式：tabBar 页面用 switchTab（不能带参数），其余用 redirectTo */
export function navigation(target: string): { method: 'switchTab' | 'redirectTo'; url: string } {
  const path = target.split('?')[0]
  return TAB_PAGES.includes(path) ? { method: 'switchTab', url: path } : { method: 'redirectTo', url: target }
}

/** 打开登录授权页的地址，授权完成后回到 from */
export function loginUrl(from?: string): string {
  return from ? `${LOGIN_PAGE}?redirect=${encodeURIComponent(from)}` : LOGIN_PAGE
}

export const AGREED_KEY = 'sp_agreed_version'

/** 已同意的是否为当前版本的协议；协议更新后需要重新同意 */
export function agreedCurrent(stored: unknown): boolean {
  return stored === AGREEMENT_VERSION
}

export type NeedPhone = 'ok' | 'need-phone'

/** 需要手机号的操作（绑定设备等）前判断 */
export function needPhone(user: Pick<UserProfile, 'phoneBound'> | null | undefined): NeedPhone {
  return user?.phoneBound ? 'ok' : 'need-phone'
}
