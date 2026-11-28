import { api } from '../api/index'
import { loginUrl, needPhone } from './flow'

/**
 * 需要手机号的操作前调用（如扫码绑定，PRD 5.2）：已授权返回 true；否则打开登录授权页，授权后回到 from 页面，返回 false。
 *
 * @param from 当前页面完整路径（可带查询串），如 `/pages/bind/bind?sn=SP2612000123`
 */
export async function requirePhone(from: string): Promise<boolean> {
  const session = await api.ensureLogin()
  if (needPhone(session.user) === 'ok') return true
  wx.navigateTo({ url: loginUrl(from) })
  return false
}
